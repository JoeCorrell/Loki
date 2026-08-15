package com.thor.feature.stream.session

import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.thor.core.model.SessionQuality
import com.thor.core.streaming.StreamTouch
import com.thor.feature.stream.panel.PanelMode
import com.thor.feature.stream.panel.StreamKeyboardPanel
import com.thor.feature.stream.panel.StreamPanelController
import com.thor.feature.stream.panel.StreamQuickSettings

/**
 * Draws and controls the PC's independently captured second display.
 *
 * The same [StreamTouch] implementation drives both panels. The only difference
 * is its display index, which removes the previous conflict where the top panel
 * used absolute coordinates while this panel used relative trackpad movement
 * and therefore could not guarantee that the cursor ever entered monitor two.
 */
@Composable
internal fun SecondDisplayPanel(
    surface: SecondDisplaySurface,
    quality: SessionQuality,
    controller: StreamPanelController,
) {
    val videoSize by surface.videoSize.collectAsState()
    val touch = remember(surface) {
        StreamTouch(
            displayIndex = StreamTouch.SECONDARY_DISPLAY_INDEX,
            alwaysEnabled = true,
        )
    }
    touch.updateSettings(quality)
    touch.updateVideoSize(videoSize)

    DisposableEffect(touch) {
        onDispose { touch.releaseAll() }
    }

    val overlayVisible = controller.keyboardFocused || controller.mode == PanelMode.SETTINGS
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) touch.releaseAll()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        /*
         * Always composed, including while an overlay is open.
         *
         * Removing this view destroys the SurfaceTexture and forces the second
         * decoder through a detach/reconfigure cycle. The keyboard is therefore
         * a sibling above it, not an alternative branch in its place.
         */
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SecondDisplayTextureView(
                    context = context,
                    target = surface,
                    touch = touch,
                    onTwoFingerTap = controller::toggleKeyboard,
                )
            },
            onRelease = SecondDisplayTextureView::releaseOutputSurface,
        )

        when {
            controller.keyboardFocused -> StreamKeyboardPanel(
                controller = controller,
                onDismiss = controller::releaseKeyboard,
                label = "Typing on the PC · two-finger tap or B closes",
                claimControllerOnTouch = false,
                modifier = Modifier
                    .fillMaxSize()
                    .onStationaryTwoFingerTap(controller::toggleKeyboard),
            )

            controller.mode == PanelMode.SETTINGS -> StreamQuickSettings(
                quality = quality,
                onClose = controller::showPad,
                modifier = Modifier
                    .fillMaxSize()
                    .onStationaryTwoFingerTap(controller::takeKeyboard),
            )
        }
    }
}

/**
 * A GPU-composited decoder target for the Thor's presentation display.
 *
 * The lower panel is a separate Android [android.app.Presentation]. A
 * `SurfaceView` creates another hardware-composer layer below that window; on
 * the Thor the Qualcomm decoder can queue its UBWC buffer to that layer, but the
 * secondary display never scans it out and remains black. The project's movie
 * player uses [TextureView] for the same boundary: its [SurfaceTexture] is
 * composed into the presentation window by the GPU, which the lower panel can
 * display normally.
 */
private class SecondDisplayTextureView(
    context: android.content.Context,
    private val target: SecondDisplaySurface,
    private val touch: StreamTouch,
    private val onTwoFingerTap: () -> Unit,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var outputSurface: Surface? = null
    private val keyboardGesture = TwoFingerTapGesture()

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        isOpaque = true
        setOnTouchListener { view, event ->
            val toggled = keyboardGesture.onMotionEvent(event)
            val handled = touch.onTouch(view, event)
            if (toggled) onTwoFingerTap()
            handled || toggled
        }
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        releaseOutputSurface()
        Surface(texture).also { surface ->
            outputSurface = surface
            target.publish(surface, width, height)
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        outputSurface?.let { surface -> target.publish(surface, width, height) }
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releaseOutputSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** Stops native rendering before releasing the Java Surface wrapper. */
    fun releaseOutputSurface() {
        keyboardGesture.cancel()
        touch.releaseAll()
        outputSurface?.let { surface ->
            target.clear(surface)
            surface.release()
        }
        outputSurface = null
    }

    /** Releases a remote drag if another Android window interrupts the session. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) touch.releaseAll()
    }

    /** Exposes click semantics to Android accessibility services. */
    override fun performClick(): Boolean = super.performClick()
}

/** Feeds a raw decoder-view event into the platform-neutral gesture tracker. */
private fun TwoFingerTapGesture.onMotionEvent(event: MotionEvent): Boolean {
    val pointers = event.gesturePointers()
    return when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            cancel()
            pointerDown(pointers[event.actionIndex], event.eventTime)
            false
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            pointersMoved(pointers)
            pointerDown(pointers[event.actionIndex], event.eventTime)
            false
        }

        MotionEvent.ACTION_MOVE -> {
            pointersMoved(pointers)
            false
        }

        MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_UP,
        -> {
            pointersMoved(pointers)
            pointerUp(pointers[event.actionIndex], event.eventTime)
        }

        MotionEvent.ACTION_CANCEL -> {
            cancel()
            false
        }

        else -> false
    }
}

/** Copies Android pointer coordinates before the event object is recycled. */
private fun MotionEvent.gesturePointers(): List<GesturePointer> =
    List(pointerCount) { index ->
        GesturePointer(
            id = getPointerId(index).toLong(),
            x = getX(index),
            y = getY(index),
        )
    }

/**
 * Toggles an overlay without allowing the two releases to click keyboard keys.
 *
 * The Initial pass observes the gesture before the keyboard's child clickables.
 * Once the second finger lands, every remaining change is consumed through the
 * final release. A moving gesture, third finger, or hold is still swallowed from
 * the keyboard but deliberately does not invoke [onTap].
 */
private fun Modifier.onStationaryTwoFingerTap(onTap: () -> Unit): Modifier =
    pointerInput(onTap) {
        awaitEachGesture {
            val gesture = TwoFingerTapGesture()
            var interceptUntilRelease = false
            var toggled = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointers = event.changes.map { change ->
                    GesturePointer(
                        id = change.id.value,
                        x = change.position.x,
                        y = change.position.y,
                    )
                }

                gesture.pointersMoved(pointers)
                event.changes
                    .filter { change -> change.pressed && !change.previousPressed }
                    .forEach { change ->
                        gesture.pointerDown(
                            pointer = GesturePointer(
                                id = change.id.value,
                                x = change.position.x,
                                y = change.position.y,
                            ),
                            timeMs = change.uptimeMillis,
                        )
                    }

                if (gesture.interceptsGesture) interceptUntilRelease = true

                event.changes
                    .filter { change -> !change.pressed && change.previousPressed }
                    .forEach { change ->
                        if (
                            gesture.pointerUp(
                                pointer = GesturePointer(
                                    id = change.id.value,
                                    x = change.position.x,
                                    y = change.position.y,
                                ),
                                timeMs = change.uptimeMillis,
                            )
                        ) {
                            toggled = true
                        }
                    }

                if (interceptUntilRelease) {
                    event.changes.forEach { change -> change.consume() }
                }

                if (toggled) {
                    onTap()
                    toggled = false
                }

                if (event.changes.none { change -> change.pressed }) {
                    gesture.cancel()
                    break
                }
            }
        }
    }
