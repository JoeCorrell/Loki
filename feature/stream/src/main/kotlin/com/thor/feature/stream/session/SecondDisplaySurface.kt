package com.thor.feature.stream.session

import android.view.Surface
import com.thor.core.streaming.StreamVideoSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The second panel's drawing surface, shared between the window that owns it and
 * the session that draws into it.
 *
 * It exists because of an ordering problem that has no tidy solution. The second
 * display's surface lives in a `Presentation` on the other screen, which is
 * created by a composition effect — but the session has to be told about it
 * *before* `startConnection`, because the request travels in the SDP of the
 * ANNOUNCE that call sends. The activity's own surface and the presentation's
 * therefore race, and the stream cannot simply start when the first one is ready.
 *
 * So the activity waits, briefly, for both. This holder is what it waits on.
 */
class SecondDisplaySurface(
    /** Stops the native second decoder before Android invalidates its output. */
    private val onSurfaceDestroying: () -> Unit = {},
) {

    private val _target = MutableStateFlow<SecondDisplayTarget?>(null)

    /** The live presentation surface and its current drawable dimensions. */
    val target: StateFlow<SecondDisplayTarget?> = _target.asStateFlow()

    private val _videoSize = MutableStateFlow(DEFAULT_VIDEO_SIZE)

    /** The host-negotiated mode used to map touches through letterboxing. */
    val videoSize: StateFlow<StreamVideoSize> = _videoSize.asStateFlow()

    /**
     * Whether the panel should still be showing the PC rather than the trackpad.
     *
     * Starts true, because the surface has to be drawn *before* the session
     * starts in order to be offered at all — there is no answer to consult yet.
     * It goes false if the host declines or the stream later dies, and the panel
     * becomes the trackpad it would have been.
     *
     * Optimistic on purpose. The alternative — start as a trackpad and swap to
     * the display once granted — means every successful session visibly flickers
     * from one to the other, to spare a failing one a frame of black.
     */
    private val _active = MutableStateFlow(true)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Publishes a valid second-panel surface once Android has sized it. */
    fun publish(surface: Surface, width: Int, height: Int) {
        if (!surface.isValid || width <= 0 || height <= 0) return
        _target.value = SecondDisplayTarget(surface, width, height)
    }

    /** Clears [surface] without accidentally clearing a newer replacement. */
    fun clear(surface: Surface) {
        if (_target.value?.surface === surface) {
            /*
             * SurfaceTexture/SurfaceHolder teardown is a synchronous contract:
             * no producer may still be rendering when its destroy callback
             * returns. Detach here, before withdrawing and releasing the target,
             * rather than relying on a Flow collector that runs a frame later.
             */
            onSurfaceDestroying()
            _target.value = null
            _active.value = false
        }
    }

    fun setActive(active: Boolean) {
        _active.value = active
    }

    /** Updates the mode reported by the second `MediaCodec` decoder. */
    fun setVideoSize(size: StreamVideoSize) {
        if (size.isValid) _videoSize.value = size
    }

    /**
     * Waits for the surface, giving up after [timeoutMs].
     *
     * Returns null on timeout, and that is an ordinary outcome rather than an
     * error: the session then starts with one display, which is what every
     * session did before this feature existed. Waiting indefinitely would mean a
     * presentation that never arrives — because the panel is off, or the mode is
     * couch, or the display was unplugged between the setting and the launch —
     * hangs the stream at a black screen instead of starting it.
     *
     * The timeout is generous relative to what it is waiting for. A presentation
     * that is coming at all is composed within a frame or two; anything past that
     * is not late, it is absent.
     */
    suspend fun await(timeoutMs: Long = DEFAULT_TIMEOUT_MS): SecondDisplayTarget? =
        withTimeoutOrNull(timeoutMs) { _target.filterNotNull().first() }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 1_500L

        val DEFAULT_VIDEO_SIZE = StreamVideoSize(1920, 1080)
    }
}

/** A sized, valid output target created by the second display's Presentation. */
data class SecondDisplayTarget(
    val surface: Surface,
    val width: Int,
    val height: Int,
)
