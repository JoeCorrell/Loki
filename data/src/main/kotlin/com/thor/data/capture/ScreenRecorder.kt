package com.thor.data.capture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.thor.core.common.capture.ScreenshotBridge
import com.thor.core.common.log.ThorLog
import com.thor.core.model.RecordingAudio
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the recorder is doing. */
sealed interface RecordingState {
    data object Idle : RecordingState

    /**
     * Recording either onto [displayId] or directly from the physical displays.
     *
     * Launcher-only recordings expose their private display so Compose can draw
     * onto it. Device recordings are composed inside the foreground service and
     * set [capturesDevice], so covering or destroying Loki's window cannot stop
     * frames from reaching the encoder.
     */
    data class Active(
        /** Private Compose display for launcher-only recordings; otherwise null. */
        val displayId: Int?,
        /** True when the foreground service is capturing physical displays. */
        val capturesDevice: Boolean = false,
    ) : RecordingState

    /** The last attempt failed; carried so the UI can say what happened. */
    data class Failed(val reason: String) : RecordingState
}

/**
 * Records either Loki's UI or the physical device displays.
 *
 * Launcher-only capture draws Loki onto a private display backed by the encoder.
 * Device capture uses MediaProjection for the default panel and accessibility
 * frames for the secondary panel, composed on a GL thread owned by the service.
 *
 * So the launcher does not capture a screen at all: it draws a third copy of itself.
 * A private `VirtualDisplay` is created with the encoder's input surface as its
 * output, and the launcher renders both panels onto it, stacked, each at the full
 * size of the screen it stands for. The frames go straight from the compositor to
 * the encoder, never through a bitmap, and no permission is involved because the app
 * owns both the display and its contents.
 *
 * Both routes publish through MediaStore only after the encoder stops successfully;
 * failed or empty recordings are deleted rather than left as invisible pending files.
 */
@Singleton
class ScreenRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenshots: ScreenshotBridge,
) {

    private val displayManager: DisplayManager? =
        context.getSystemService(DisplayManager::class.java)

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projectionCompositor: RecordingProjectionCompositor? = null
    private var pendingUri: Uri? = null
    private var activeProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var bottomCaptureJob: Job? = null
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stopping = false

    val isRecording: Boolean get() = _state.value is RecordingState.Active

    /**
     * What sound the next recording captures.
     *
     * Set by the shell from the user's setting rather than read from a repository
     * here, because this class is deliberately free of settings: it is handed a
     * size, a density and a surface, and everything else about it is the caller's
     * decision. See [RecordingAudio] for why there is no game-audio option.
     */
    var audio: RecordingAudio = RecordingAudio.OFF

    /**
     * Whether the microphone has actually been granted.
     *
     * Checked rather than assumed, because `setAudioSource` does not fail where it
     * is called — it fails at `prepare()`, several lines later, by which point the
     * output file has been created and the failure reads as "recording could not
     * be started" rather than as "you have not allowed the microphone".
     */
    private fun hasMicrophonePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts a recording and returns the display to render the mock-up onto.
     *
     * @param width video width in pixels; rounded to an even number because H.264
     *   macroblocks come in pairs and an odd dimension is rejected by some encoders
     */
    fun start(width: Int, height: Int, densityDpi: Int): RecordingState {
        if (isRecording) return _state.value

        /*
         * Scaled as a pair, never clamped apart.
         *
         * Each dimension used to be coerced into range on its own, and two panels
         * stacked are tall enough for that to bite: the height hit the ceiling, the
         * width did not, and the frame the launcher was asked to draw into was a
         * different shape from the one it had measured for. Compose does not shrink
         * a column to fit — it overflows — so the bottom panel was simply cut off,
         * and a recording of two screens showed one and a half.
         *
         * One factor applied to both keeps the shape whatever the ceiling is, and
         * the density goes with it: pixels and density together decide the dp box a
         * composition is measured in, so scaling only the pixels would shrink the
         * recorded launcher's layout rather than its resolution.
         */
        val scale = captureScale(width, height)
        val videoWidth = (width * scale).toInt().roundToEven()
        val videoHeight = (height * scale).toInt().roundToEven()
        val videoDensity = (densityDpi * scale).toInt().coerceAtLeast(MIN_DENSITY)

        return begin(videoWidth, videoHeight, videoDensity) { surface ->
            /*
             * Own content only, and never mirrored: this display exists to be drawn
             * into, so anything the system might otherwise duplicate onto it — the
             * default display's content — would be exactly wrong. The presentation
             * flag is what lets a `Presentation` window attach to it.
             */
            displayManager?.createVirtualDisplay(
                DISPLAY_NAME,
                videoWidth,
                videoHeight,
                videoDensity,
                surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            ) ?: error("The device would not create a virtual display")
        }
    }

    /**
     * Records apps on both physical panels in one stacked frame.
     *
     * MediaProjection supplies the primary panel at video frame rate. Android has
     * no public projection stream for a secondary physical display, so Loki's
     * accessibility service supplies that panel at the platform's permitted
     * screenshot cadence. The service-owned compositor continues after every Loki
     * window is covered or destroyed. Consent is still required for every session.
     */
    fun startProjection(
        projection: MediaProjection,
        requestedLayout: ScreenCaptureLayout,
    ): RecordingState {
        if (isRecording) return _state.value

        if (requestedLayout.bottom != null && !screenshots.available.value) {
            projection.stop()
            return fail("Turn on Loki's accessibility service to record both screens")
        }

        val layout = requestedLayout.scaledForEncoder()
        val uri = createOutputEntry()
        if (uri == null) {
            projection.stop()
            return fail("Could not create the video file")
        }

        /*
         * The projection can end without us: the user revokes it from the system
         * UI, or the platform tears it down. Left unhandled that leaves a recording
         * of a frozen screen and a file nobody asked for.
         */
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (isRecording) stop()
            }
        }
        projection.registerCallback(callback, null)
        activeProjection = projection
        projectionCallback = callback

        return runCatching {
            val newRecorder = prepareRecorder(uri, layout.outputWidth, layout.outputHeight)
            recorder = newRecorder
            pendingUri = uri

            val compositor = RecordingProjectionCompositor(
                projection = projection,
                encoderSurface = newRecorder.surface,
                layout = layout,
                onFailure = {
                    captureScope.launch { if (isRecording) stop() }
                },
            )
            projectionCompositor = compositor
            compositor.prepare()
            newRecorder.start()
            compositor.start()

            RecordingState.Active(displayId = null, capturesDevice = true).also { active ->
                _state.value = active
                startBottomCapture(layout.bottom, compositor)
            }
        }.getOrElse { error ->
            ThorLog.e(TAG, "Could not start screen recording", error)
            releaseEverything()
            discard(uri)
            fail(error.message ?: "Screen recording could not be started")
        }
    }

    private fun startBottomCapture(
        bottom: CaptureDisplay?,
        compositor: RecordingProjectionCompositor,
    ) {
        if (bottom == null) return

        bottomCaptureJob = captureScope.launch {
            while (isActive && isRecording) {
                if (!screenshots.available.value) {
                    ThorLog.w(TAG, "Secondary-screen capture became unavailable")
                    stop()
                    break
                }

                val bitmap = runCatching { screenshots.captureBitmap(bottom.displayId) }
                    .onFailure { ThorLog.w(TAG, "Could not capture the bottom screen", it) }
                    .getOrNull()
                compositor.submitBottom(bitmap)
                delay(SECONDARY_FRAME_INTERVAL_MS)
            }
        }
    }

    /**
     * Everything a recording needs whichever picture is going into it.
     *
     * The encoder, the output file and the bookkeeping are identical for both; only
     * where the frames come from differs, which is what [display] supplies. Shared
     * rather than written twice because the failure path in particular — release the
     * encoder, delete the half-written entry, report why — is the part that is easy
     * to get subtly different in a second copy and never notice.
     */
    private fun begin(
        videoWidth: Int,
        videoHeight: Int,
        videoDensity: Int,
        display: (Surface) -> VirtualDisplay,
    ): RecordingState {
        val uri = createOutputEntry() ?: return fail("Could not create the video file")

        return runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("No file descriptor for $uri")

            /*
             * Whether sound is going in, decided once at the moment of starting.
             *
             * Read here rather than held, because `MediaRecorder` is configured
             * before `prepare()` and cannot be changed afterwards — a setting
             * changed mid-recording must not take effect until the next one, and
             * reading it once is the only way to be sure it does not.
             *
             * Falls back to silence rather than failing when the permission is
             * missing. `setAudioSource` throws at `prepare()` without RECORD_AUDIO,
             * and a recording that refuses to start because of a setting the user
             * forgot is worse than one with no sound on it.
             */
            val withAudio = audio == RecordingAudio.MICROPHONE && hasMicrophonePermission()

            val newRecorder = descriptor.use { file ->
                buildRecorder().apply {
                    // Sources before the format, and both before the encoders:
                    // MediaRecorder is a state machine and this is the only order
                    // it accepts.
                    if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    if (withAudio) {
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                        setAudioEncodingBitRate(AUDIO_BIT_RATE)
                        setAudioChannels(AUDIO_CHANNELS)
                    }
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(videoWidth, videoHeight)
                    setVideoFrameRate(FRAME_RATE)
                    setVideoEncodingBitRate(bitRateFor(videoWidth, videoHeight))
                    setOutputFile(file.fileDescriptor)
                    prepare()
                }
            }

            recorder = newRecorder
            pendingUri = uri
            val newDisplay = display(newRecorder.surface)

            newRecorder.start()

            virtualDisplay = newDisplay

            RecordingState.Active(newDisplay.display.displayId).also { _state.value = it }
        }.getOrElse { error ->
            ThorLog.e(TAG, "Could not start recording", error)
            releaseEverything()
            discard(uri)
            fail(error.message ?: "Recording could not be started")
        }
    }

    /** Configures the common MediaRecorder state machine for a projection. */
    private fun prepareRecorder(uri: Uri, videoWidth: Int, videoHeight: Int): MediaRecorder {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: error("No file descriptor for $uri")
        val withAudio = audio == RecordingAudio.MICROPHONE && hasMicrophonePermission()

        return descriptor.use { file ->
            buildRecorder().apply {
                if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withAudio) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                    setAudioEncodingBitRate(AUDIO_BIT_RATE)
                    setAudioChannels(AUDIO_CHANNELS)
                }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(videoWidth, videoHeight)
                setVideoFrameRate(FRAME_RATE)
                setVideoEncodingBitRate(bitRateFor(videoWidth, videoHeight))
                setOutputFile(file.fileDescriptor)
                prepare()
            }
        }
    }

    /**
     * Stops and publishes the file.
     *
     * @return the file's display name when something was written, or null
     */
    @Synchronized
    fun stop(): String? {
        if (stopping) return null
        stopping = true

        return try {
            val uri = pendingUri
            val active = recorder

            /*
             * `stop()` throws when the encoder was handed no frames at all, which
             * means recording ended within a frame or two. Discard that empty file.
             */
            projectionCompositor?.pause()
            val wrote = active != null && runCatching { active.stop() }.isSuccess
            releaseEverything()

            _state.value = RecordingState.Idle

            if (uri == null) return null
            if (!wrote) {
                discard(uri)
                return null
            }
            publish(uri)
        } finally {
            stopping = false
        }
    }

    /** API 31 deprecated the parameterless constructor; both are still needed. */
    private fun buildRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun createOutputEntry(): Uri? = runCatching {
        val name = "Loki-${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, OUTPUT_DIRECTORY)
            // Hidden from galleries until it is finished, so a half-written file is
            // never offered to the user as a video.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    private fun publish(uri: Uri): String? = runCatching {
        val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun discard(uri: Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private fun releaseEverything() {
        bottomCaptureJob?.cancel()
        bottomCaptureJob = null
        // Stop feeding frames before resetting the encoder surface they target.
        runCatching { projectionCompositor?.release() }
        projectionCompositor = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null

        val projection = activeProjection
        val callback = projectionCallback
        activeProjection = null
        projectionCallback = null
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        runCatching { projection?.stop() }
        pendingUri = null
    }

    private fun fail(reason: String): RecordingState =
        RecordingState.Failed(reason).also { _state.value = it }

    /** Roughly 0.15 bits per pixel per frame, which is generous for flat UI. */
    private fun bitRateFor(width: Int, height: Int): Int =
        (width.toLong() * height * FRAME_RATE * 15 / 100)
            .coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)
            .toInt()

    private fun Int.roundToEven(): Int = this - (this % 2)

    private companion object {
        const val TAG = "Recorder"
        const val DISPLAY_NAME = "Loki capture"
        const val OUTPUT_DIRECTORY = "Movies/Loki"
        const val FRAME_RATE = 30
        // Android rate-limits AccessibilityService screenshots to one per 333ms.
        const val SECONDARY_FRAME_INTERVAL_MS = 350L

        /** Enough for speech and game audio off a handheld's speakers. */
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_BIT_RATE = 128_000

        /** One channel: the microphone on this device is mono whatever is asked. */
        const val AUDIO_CHANNELS = 1
        const val MIN_DENSITY = 160
        const val MIN_BIT_RATE = 2_000_000L
        const val MAX_BIT_RATE = 24_000_000L
    }
}

/**
 * One factor that brings both video dimensions inside the encoder's range.
 *
 * Shrinks when the longer side is over the ceiling — which two stacked panels reach
 * on any device with 1080p screens — and grows when the shorter side is under the
 * floor. Returns 1 when the frame already fits, so the ordinary case is recorded at
 * exactly the panels' own resolution.
 *
 * Top level, and `internal`, so it can be tested without a `Context`: the rule it
 * encodes is the one that broke the recorder, and it broke silently — a clamped
 * dimension produces a valid file of the wrong shape, with no error anywhere.
 */
internal fun captureScale(
    width: Int,
    height: Int,
    minDimension: Int = CAPTURE_MIN_DIMENSION,
    maxDimension: Int = CAPTURE_MAX_DIMENSION,
): Float {
    if (width <= 0 || height <= 0) return 1f

    val shrink = maxDimension.toFloat() / maxOf(width, height)
    val grow = minDimension.toFloat() / minOf(width, height)

    return when {
        shrink < 1f -> shrink
        grow > 1f -> grow
        else -> 1f
    }
}

/** Smallest side an encoder here is asked to accept. */
internal const val CAPTURE_MIN_DIMENSION = 240

/** Largest side. Two 1080p panels stacked land exactly on it. */
internal const val CAPTURE_MAX_DIMENSION = 2160
