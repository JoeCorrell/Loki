package com.thor.core.streaming

import android.view.Surface
import com.limelight.nvstream.NvConnectionListener
import com.limelight.nvstream.jni.MoonBridge
import com.thor.core.common.log.ThorLog
import com.thor.core.model.StreamAudio
import com.thor.core.model.StreamCodec
import com.thor.core.model.StreamNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** Where a session has got to, as one value the screen can render. */
sealed interface SessionState {

    data object Idle : SessionState

    /** Connecting, with the core's own name for the step it is on. */
    data class Starting(val stage: String) : SessionState

    data object Streaming : SessionState

    /** Ended, either because the user asked or because it broke. */
    data class Ended(val reason: String?, val byUser: Boolean) : SessionState
}

/**
 * One streaming session, from connect to teardown.
 *
 * The Kotlin side of the vendored core: it owns the renderers, receives the
 * core's callbacks, and turns them into a state the UI can show. Deliberately
 * not a `@Singleton` — a session is a thing with a lifetime, created for one
 * game and thrown away after, and a long-lived one would carry a dead surface
 * into the next launch.
 *
 * Everything here happens on threads owned by C. Nothing in this class touches
 * the UI directly; it publishes state and lets the screen collect it.
 */
class StreamSession(
    val launched: LaunchedSession,
    private val surface: Surface,
    /**
     * The second panel, when it is being used as a second display.
     *
     * Null for an ordinary session, which is every session where the panel shows
     * the trackpad instead — and every session against a host that does not
     * implement the extension, because the request is refused during RTSP.
     */
    private val secondSurface: Surface? = null,
    /** The second display's requested mode; ignored when [secondSurface] is null. */
    private val secondDisplay: SecondDisplayRequest? = null,
) : NvConnectionListener {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _primaryVideoSize = MutableStateFlow(
        StreamVideoSize(launched.width, launched.height),
    )

    /** The primary mode requested initially and replaced by the decoder's real mode. */
    val primaryVideoSize: StateFlow<StreamVideoSize> = _primaryVideoSize.asStateFlow()

    private val _secondVideoSize = MutableStateFlow(
        secondDisplay?.let { StreamVideoSize(it.width, it.height) },
    )

    /** The second stream's negotiated mode, or null when no second display was requested. */
    val secondVideoSize: StateFlow<StreamVideoSize?> = _secondVideoSize.asStateFlow()

    private val decoder = ThorVideoDecoder(
        surface = surface,
        onError = { message -> fail(message) },
        onVideoSizeChanged = { size -> _primaryVideoSize.value = size },
        streamName = "primary",
    )

    /**
     * The second display's decoder.
     *
     * Failures here do not call [fail]. A second stream that dies must cost the
     * user a screen rather than the game they are playing, which is the same rule
     * the native side follows — see `SecondStreamListenerCallbacks`.
     */
    private val decoder2 = secondSurface
        ?.takeIf { launched.server.maxVideoStreams >= REQUIRED_VIDEO_STREAMS }
        ?.let { second ->
            ThorVideoDecoder(
                surface = second,
                onError = { message ->
                    ThorLog.w(TAG, "Second display decoder failed: $message")
                    _secondDisplayActive.value = false
                },
                onVideoSizeChanged = { size -> _secondVideoSize.value = size },
                streamName = "secondary",
            )
        }

    private val _secondDisplayActive = MutableStateFlow(false)

    /**
     * Whether the second panel is currently showing the PC's second display.
     *
     * False until the connection is up and the host has agreed. The panel watches
     * this to decide between the desktop and the trackpad, because a host may
     * decline and a panel left waiting for frames that never arrive is worse than
     * one that never offered.
     */
    val secondDisplayActive: StateFlow<Boolean> = _secondDisplayActive.asStateFlow()

    private val audio = ThorAudioRenderer()

    /**
     * Whether teardown has already run.
     *
     * The core can report termination while the user is also backing out, and
     * `stopConnection` is not safe to call twice — the second call is into a
     * connection that has already freed itself.
     */
    private val stopped = AtomicBoolean(false)

    /**
     * Starts the session. Blocks until the core has taken over or refused.
     *
     * Called off the main thread by the caller: `startConnection` performs the
     * whole RTSP negotiation inline, which is several round trips to the host.
     */
    fun start(): Boolean {
        _state.value = SessionState.Starting("connecting")

        MoonBridge.setupBridge(decoder, audio, this)

        val quality = launched.quality

        /*
         * Ask for a second display, before the connection starts.
         *
         * It has to be armed here rather than after: the request travels in the
         * SDP of the ANNOUNCE that `startConnection` sends, so anything set
         * afterwards would arrive a whole negotiation too late.
         *
         * Asking is not getting. The host may not implement the extension, may
         * have no display to give, or may refuse the mode — all of which are
         * discovered during RTSP inside `startConnection` and reported by
         * `isSecondDisplayActive` once it returns.
         */
        if (decoder2 != null && secondDisplay != null) {
            MoonBridge.setupSecondDisplayBridge(
                decoder2,
                secondDisplay.width,
                secondDisplay.height,
                secondDisplay.fps,
                secondDisplay.bitrateKbps,
                decoder2.getCapabilities(),
            )
        }

        if (secondSurface != null && launched.server.maxVideoStreams < REQUIRED_VIDEO_STREAMS) {
            ThorLog.i(
                TAG,
                "Host advertises ${launched.server.maxVideoStreams} video stream; " +
                    "keeping the second panel as a trackpad",
            )
        }

        val result = MoonBridge.startConnection(
            launched.host.address,
            launched.server.appVersion,
            launched.server.gfeVersion,
            launched.rtspSessionUrl,
            launched.server.codecModeSupport,
            launched.width,
            launched.height,
            launched.fps,
            launched.bitrateKbps,
            /*
             * Packet size, which follows the network setting.
             *
             * A local network carries a full-sized packet happily. Anything
             * crossing a VPN or the internet has a smaller usable MTU, and a
             * packet that has to be fragmented costs far more than a slightly
             * small one — Tailscale in particular reduces it, and this launcher
             * is routinely used over it. On automatic the core decides from the
             * address, which is what Moonlight does.
             */
            when (quality.network) {
                StreamNetwork.LOCAL -> LOCAL_PACKET_SIZE
                else -> REMOTE_PACKET_SIZE
            },
            when (quality.network) {
                StreamNetwork.LOCAL -> STREAM_CFG_LOCAL
                StreamNetwork.REMOTE -> STREAM_CFG_REMOTE
                StreamNetwork.AUTO -> STREAM_CFG_AUTO
            },
            // Matching what the host was asked for at launch; asking the core
            // for a different shape than the host is encoding produces silence.
            audioConfiguration(quality.audio),
            supportedVideoFormats(quality.codec),
            launched.fps * 100,
            launched.riKey,
            launched.riKeyIv,
            decoder.getCapabilities(),
            MoonBridge.COLORSPACE_REC_709,
            MoonBridge.COLOR_RANGE_LIMITED,
        )

        if (result != 0) {
            /*
             * A non-zero return means the core never started, and — importantly
             * — that it has already cleaned itself up. Calling `stopConnection`
             * now would be tearing down a connection that does not exist.
             *
             * The *bridge* is a different matter and does have to be cleared.
             * `setupBridge` put this session, its decoder and its audio renderer
             * into static fields of `MoonBridge`, and a failed start left them
             * there — so the decoder and the surface it holds stayed reachable
             * until some later session happened to overwrite them. One leaked
             * surface per failed launch, which is exactly the case a user retries
             * several times in a row.
             */
            stopped.set(true)
            runCatching { MoonBridge.cleanupBridge() }
            fail("The stream would not start (error $result).")
            return false
        }

        /*
         * Whether the second display actually happened.
         *
         * Asked only now, because the answer is not known until RTSP has run: the
         * host advertises the capability, but the SETUP for `streamid=video/1/0`
         * is where it is granted or refused. Publishing it as state lets the panel
         * fall back to the trackpad rather than sit black waiting for frames that
         * are never coming.
         */
        if (decoder2 != null) {
            val active = runCatching { MoonBridge.isSecondDisplayActive() }.getOrDefault(false)
            _secondDisplayActive.value = active
            if (!active) {
                ThorLog.i(TAG, "Host did not provide a second display; the panel keeps the trackpad")
            }
        }

        return true
    }

    /** Ends the session at the user's request. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        _secondDisplayActive.value = false
        _state.value = SessionState.Ended(reason = null, byUser = true)
        runCatching { MoonBridge.stopConnection() }
        runCatching { MoonBridge.cleanupBridge() }
    }

    /**
     * Stops and releases only the optional lower-screen video pipeline.
     *
     * Android may destroy a presentation surface while the primary activity and
     * connection remain healthy. Continuing to submit frames to that stale
     * surface crashes inside `MediaCodec`; ending the whole connection instead
     * needlessly drops video, audio, and input from the top screen.
     */
    fun detachSecondDisplay() {
        _secondDisplayActive.value = false
        runCatching { MoonBridge.detachSecondDisplayBridge() }
            .onFailure { ThorLog.w(TAG, "Could not detach the second display", it) }
    }

    // --- What the core tells us ------------------------------------------------

    override fun stageStarting(stage: String) {
        ThorLog.i(TAG, "Stage: $stage")
        _state.update { current ->
            // Only while connecting: a stage report arriving after the stream is
            // up would otherwise send the screen back to its loading state.
            if (current is SessionState.Starting) SessionState.Starting(stage) else current
        }
    }

    override fun stageComplete(stage: String) = Unit

    override fun stageFailed(stage: String, portFlags: Int, errorCode: Int) {
        /*
         * Names the ports involved, because that is the actionable half.
         *
         * A failed stage is almost always a blocked port rather than a bug, and
         * the core can say which — so "failed while starting the video stream"
         * becomes "UDP 47998 is not getting through", which is a firewall rule
         * the user can go and add.
         */
        val ports = runCatching { MoonBridge.stringifyPortFlags(portFlags, ", ") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

        fail(
            buildString {
                append("Failed while $stage")
                if (ports != null) append(". Check that $ports can reach the PC")
                if (errorCode != 0) append(" (error $errorCode)")
                append(".")
            },
        )
    }

    override fun connectionStarted() {
        ThorLog.i(TAG, "Streaming ${launched.app.title}")

        /*
         * Tells the host what the controller is, now that there is a session to
         * tell.
         *
         * The launch request said a pad is attached; this says what kind. Without
         * it the host knows only that *something* is there, and games that adapt
         * their prompts to the controller — most of them — have nothing to go on,
         * while triggers may be treated as buttons because nothing said they are
         * analogue.
         *
         * Announced as an Xbox pad because that is the layout the handheld has
         * and the one Windows understands without a driver. Rumble is claimed
         * because the protocol will send the events; whether the device has
         * motors is a separate question, and one THOR answers by ignoring them.
         */
        runCatching {
            MoonBridge.sendControllerArrivalEvent(
                CONTROLLER_NUMBER,
                ACTIVE_GAMEPAD_MASK,
                MoonBridge.LI_CTYPE_XBOX,
                SUPPORTED_BUTTONS,
                // Widened before combining: Kotlin's Short has no bitwise
                // operators, so the constants have to meet as Ints.
                (
                    MoonBridge.LI_CCAP_ANALOG_TRIGGERS.toInt() or
                        MoonBridge.LI_CCAP_RUMBLE.toInt()
                    ).toShort(),
            )
        }.onFailure { ThorLog.w(TAG, "Could not announce the controller", it) }

        _state.value = SessionState.Streaming
    }

    override fun connectionTerminated(errorCode: Int) {
        if (!stopped.compareAndSet(false, true)) return

        _secondDisplayActive.value = false
        /*
         * Zero is a clean end: the host stopped, or the game exited. Anything
         * else is a fault worth naming — the two look identical from the user's
         * side, which is why an unexplained return to the launcher reads as a
         * crash.
         */
        _state.value = if (errorCode == 0) {
            SessionState.Ended(reason = null, byUser = false)
        } else {
            SessionState.Ended(reason = "The stream ended unexpectedly (error $errorCode).", byUser = false)
        }
        // The callback reports termination; it does not free the connection.
        // Without this call the primary/control/audio/input threads survive with
        // Java renderer references cleared below, poisoning the next reconnect.
        runCatching { MoonBridge.stopConnection() }
        runCatching { MoonBridge.cleanupBridge() }
    }

    override fun connectionStatusUpdate(connectionStatus: Int) {
        ThorLog.i(TAG, "Connection status: $connectionStatus")
    }

    override fun secondDisplayStatusChanged(active: Boolean, errorCode: Int) {
        /*
         * Validate a positive callback against native's current state.
         *
         * A receive failure and the startup notification are produced by
         * different native threads. Even though native serializes its state, an
         * already-dispatched positive callback can arrive after the failure. A
         * negative transition is final, while a positive one is accepted only
         * if the stream is still negotiated and running at this instant.
         */
        val usable = active && runCatching { MoonBridge.isSecondDisplayActive() }
            .getOrDefault(false)
        _secondDisplayActive.value = usable
        if (!usable && errorCode != 0) {
            ThorLog.w(TAG, "Second display stopped with error $errorCode; primary stream continues")
        }
    }

    override fun displayMessage(message: String) {
        ThorLog.i(TAG, message)
    }

    override fun displayTransientMessage(message: String) {
        ThorLog.i(TAG, message)
    }

    // Rumble and the rest arrive on the core's threads and are accepted so the
    // interface is satisfied; wiring them to the handheld's motors belongs with
    // the input path, which sends events the other way.
    override fun rumble(controllerNumber: Short, lowFreqMotor: Short, highFreqMotor: Short) = Unit

    override fun rumbleTriggers(controllerNumber: Short, leftTrigger: Short, rightTrigger: Short) =
        Unit

    override fun setHdrMode(enabled: Boolean, hdrMetadata: ByteArray?) {
        decoder.setHdrMode(enabled, hdrMetadata)
    }

    override fun setMotionEventState(controllerNumber: Short, motionType: Byte, reportRateHz: Short) =
        Unit

    override fun setControllerLED(controllerNumber: Short, r: Byte, g: Byte, b: Byte) = Unit

    private fun fail(message: String) {
        ThorLog.w(TAG, message)
        _state.value = SessionState.Ended(reason = message, byUser = false)
    }

    private companion object {
        const val TAG = "Stream"

        /** Primary plus the independently captured second display. */
        const val REQUIRED_VIDEO_STREAMS = 2

        /** Moonlight's figure for a link that might be a VPN or the internet. */
        const val REMOTE_PACKET_SIZE = 1024

        /** The handheld's own controls, which are always pad one and always there. */
        const val CONTROLLER_NUMBER: Byte = 0
        const val ACTIVE_GAMEPAD_MASK: Short = 0x1

        /**
         * Every button a standard pad has, which is what this device has.
         *
         * The face and shoulder buttons, both sticks, the D-pad, Start and
         * Select — the bits `ControllerPacket` defines, minus the paddles and
         * touchpad that only elaborate controllers carry.
         */
        const val SUPPORTED_BUTTONS = 0xFFFF

        /**
         * Let the core decide whether this link is local or remote.
         *
         * Lives in Moonlight's `StreamConfiguration`, which THOR does not vendor
         * — the rest of that class describes settings this launcher models
         * itself — so the one value that matters is written out here. It is part
         * of the protocol and does not change.
         */
        const val STREAM_CFG_LOCAL = 0
        const val STREAM_CFG_REMOTE = 1
        const val STREAM_CFG_AUTO = 2

        /** A LAN carries a full-sized packet; Moonlight uses this for local. */
        const val LOCAL_PACKET_SIZE = 1392

        /**
         * Which codecs THOR will accept, decided by what the device can decode.
         *
         * Asked of the platform rather than assumed: offering a format the
         * device cannot decode means the host encodes it, the stream connects,
         * and nothing ever appears on screen — a failure with no error in it.
         * H.264 is always included as the floor every GameStream host speaks.
         */
        fun supportedVideoFormats(codec: StreamCodec): Int {
            /*
             * A chosen codec still has to be one the device can decode.
             *
             * Offering a format with no hardware decoder behind it produces a
             * session that connects, negotiates and then shows nothing — so a
             * request for AV1 on a device without an AV1 decoder falls back
             * rather than being honoured into a black screen.
             */
            val hevc = ThorVideoDecoder.supports(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC)
            val av1 = ThorVideoDecoder.supports(android.media.MediaFormat.MIMETYPE_VIDEO_AV1)

            return when (codec) {
                StreamCodec.H264 -> MoonBridge.VIDEO_FORMAT_H264
                StreamCodec.HEVC ->
                    if (hevc) MoonBridge.VIDEO_FORMAT_H265 else MoonBridge.VIDEO_FORMAT_H264

                StreamCodec.AV1 ->
                    if (av1) MoonBridge.VIDEO_FORMAT_AV1_MAIN8 else MoonBridge.VIDEO_FORMAT_H264

                StreamCodec.AUTO -> {
                    // H.264 is always included as the floor every host speaks.
                    var formats = MoonBridge.VIDEO_FORMAT_H264
                    if (hevc) formats = formats or MoonBridge.VIDEO_FORMAT_H265
                    if (av1) formats = formats or MoonBridge.VIDEO_FORMAT_AV1_MAIN8
                    formats
                }
            }
        }

        /** The core's own encoding of a channel layout. */
        fun audioConfiguration(audio: StreamAudio): Int = when (audio) {
            StreamAudio.STEREO -> MoonBridge.AUDIO_CONFIGURATION_STEREO
            StreamAudio.SURROUND_51 -> MoonBridge.AUDIO_CONFIGURATION_51_SURROUND
            StreamAudio.SURROUND_71 -> MoonBridge.AUDIO_CONFIGURATION_71_SURROUND
        }.toInt()
    }
}
