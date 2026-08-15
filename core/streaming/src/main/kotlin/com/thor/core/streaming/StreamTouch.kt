package com.thor.core.streaming

import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.limelight.nvstream.jni.MoonBridge
import com.thor.core.common.log.ThorLog
import com.thor.core.model.SessionQuality
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Drives one streamed Windows display from the Android panel showing it.
 *
 * The display index is carried with every absolute pointer packet. That is the
 * piece a dual-monitor client cannot infer on the host: the same normalized
 * coordinate can mean the primary display or the virtual secondary display.
 * Index zero remains wire-compatible with an ordinary Moonlight session and
 * index one selects Sunshine DS's second capture viewport.
 *
 * One finger is direct manipulation: a tap clicks, movement becomes a held
 * left-button drag, and a long press holds the button before movement begins.
 * Two fingers scroll. The pointer position itself is mapped through
 * [StreamViewport], so black letterbox bars and a host-selected fallback mode
 * do not stretch or offset input.
 */
class StreamTouch(
    private val displayIndex: Short = PRIMARY_DISPLAY_INDEX,
    /** The second video panel must remain touchable even when top-video touch is off. */
    private val alwaysEnabled: Boolean = false,
) : View.OnTouchListener {

    private var enabled = alwaysEnabled
    private var tapToClick = true
    private var naturalScroll = true

    @Volatile
    private var videoSize = StreamVideoSize(1, 1)

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    /** Set once a gesture has travelled far enough that it cannot be a tap. */
    private var moved = false

    /** Where the last two-finger scroll was, so movement can be measured from it. */
    private var lastScrollY = 0f
    private var scrolling = false

    /** Whether a left-button press was sent and therefore must be released. */
    private var buttonDown = false

    /** The view holding the delayed long-press callback. */
    private var activeView: View? = null

    private val longPress = Runnable {
        val view = activeView ?: return@Runnable
        if (!enabled || moved || scrolling || buttonDown) return@Runnable

        sendPosition(view, downX, downY)
        sendButton(BUTTON_PRESS, BUTTON_LEFT)
        buttonDown = true
    }

    /** Applies pointer preferences without changing the negotiated video mode. */
    fun updateSettings(quality: SessionQuality) {
        enabled = alwaysEnabled || quality.touchVideoAsPointer
        tapToClick = quality.tapToClick
        naturalScroll = quality.naturalScroll
    }

    /**
     * Updates the decoded mode used for coordinate mapping.
     *
     * The decoder calls this with the mode the host actually selected, which
     * may differ from the mode requested in settings.
     */
    fun updateVideoSize(size: StreamVideoSize) {
        if (size.isValid) videoSize = size
    }

    /**
     * Releases any held remote button and cancels delayed gesture work.
     *
     * Called when a surface disappears or its window loses focus. Android does
     * not guarantee an `ACTION_CANCEL` in either case, and leaving a remote
     * button held makes the Windows desktop keep dragging after the panel is
     * gone.
     */
    fun releaseAll() {
        activeView?.removeCallbacks(longPress)
        activeView = null
        scrolling = false
        moved = false
        if (buttonDown) {
            sendButton(BUTTON_RELEASE, BUTTON_LEFT)
            buttonDown = false
        }
    }

    /**
     * @return true when [event] was consumed as touchscreen pointer input.
     *
     * Stylus and physical-mouse events are declined so their own pressure,
     * hover, and button semantics can be forwarded by the mouse/pen path.
     */
    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (!enabled || view.width <= 0 || view.height <= 0 || !event.isFingerTouch) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseAll()
                activeView = view
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                scrolling = false

                // Position first: a later button event acts where the finger is,
                // not wherever the Windows pointer happened to be left.
                sendPosition(view, event.x, event.y)
                view.postDelayed(longPress, LONG_PRESS_TIME_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    view.removeCallbacks(longPress)
                    if (buttonDown) {
                        sendButton(BUTTON_RELEASE, BUTTON_LEFT)
                        buttonDown = false
                    }
                    scrolling = true
                    moved = true
                    lastScrollY = midpointY(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling && event.pointerCount >= 2) {
                    val y = midpointY(event)
                    val delta = y - lastScrollY
                    if (abs(delta) >= SCROLL_STEP_PX) {
                        sendScroll(delta)
                        lastScrollY = y
                    }
                    return true
                }

                if (!scrolling) {
                    val crossedSlop = abs(event.x - downX) > TAP_SLOP_PX ||
                        abs(event.y - downY) > TAP_SLOP_PX
                    if (crossedSlop && !moved) {
                        moved = true
                        view.removeCallbacks(longPress)

                        // Begin the drag at the finger-down point, then move to
                        // this event. Pressing only at the current point would
                        // miss the title bar or selection handle being dragged.
                        sendPosition(view, downX, downY)
                        sendButton(BUTTON_PRESS, BUTTON_LEFT)
                        buttonDown = true
                    }

                    lastX = event.x
                    lastY = event.y
                    sendPosition(view, lastX, lastY)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor the remaining finger and permanently rule out a tap.
                if (event.pointerCount == 2) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    scrolling = false
                    moved = true
                }
            }

            MotionEvent.ACTION_UP -> {
                view.removeCallbacks(longPress)
                sendPosition(view, event.x, event.y)

                if (buttonDown) {
                    sendButton(BUTTON_RELEASE, BUTTON_LEFT)
                    buttonDown = false
                } else if (shouldClickOnRelease(moved, tapToClick)) {
                    view.performClick()
                    click()
                }

                activeView = null
                scrolling = false
            }

            MotionEvent.ACTION_CANCEL -> releaseAll()

            else -> return false
        }
        return true
    }

    /** Sends a point in the fitted stream viewport, tagged for this display. */
    private fun sendPosition(view: View, x: Float, y: Float) {
        val position = StreamViewport.map(
            viewX = x,
            viewY = y,
            viewWidth = view.width,
            viewHeight = view.height,
            videoSize = videoSize,
        ) ?: return

        runCatching {
            MoonBridge.sendMousePositionForDisplay(
                position.x.toProtocolShort(),
                position.y.toProtocolShort(),
                position.referenceWidth.toProtocolShort(minimum = 1),
                position.referenceHeight.toProtocolShort(minimum = 1),
                displayIndex,
            )
        }.onFailure { ThorLog.w(TAG, "Could not send a pointer position", it) }
    }

    private fun click() {
        sendButton(BUTTON_PRESS, BUTTON_LEFT)
        sendButton(BUTTON_RELEASE, BUTTON_LEFT)
    }

    private fun sendButton(action: Byte, button: Byte) {
        runCatching { MoonBridge.sendMouseButton(action, button) }
            .onFailure { ThorLog.w(TAG, "Could not send a mouse button", it) }
    }

    private fun sendScroll(pixels: Float) {
        runCatching {
            val direction = if (naturalScroll) -1f else 1f
            val amount = (pixels / SCROLL_STEP_PX * SCROLL_UNITS_PER_STEP * direction)
                .coerceIn(-SCROLL_CLAMP, SCROLL_CLAMP)
            MoonBridge.sendMouseHighResScroll(amount.roundToInt().toShort())
        }.onFailure { ThorLog.w(TAG, "Could not send a scroll", it) }
    }

    private fun midpointY(event: MotionEvent): Float =
        (event.getY(0) + event.getY(1)) / 2f

    companion object {
        private const val TAG = "Stream"

        /** The ordinary GameStream video/capture viewport. */
        const val PRIMARY_DISPLAY_INDEX: Short = 0

        /** Sunshine DS's independently captured virtual-display viewport. */
        const val SECONDARY_DISPLAY_INDEX: Short = 1

        /** Mouse button events, as `MouseButtonPacket` numbers them. */
        private const val BUTTON_PRESS: Byte = 0x07
        private const val BUTTON_RELEASE: Byte = 0x08
        private const val BUTTON_LEFT: Byte = 0x01

        /** Far enough to be deliberate movement rather than touchscreen jitter. */
        private const val TAP_SLOP_PX = 16f

        /** Holding this long presses the remote button before movement begins. */
        private const val LONG_PRESS_TIME_MS = 500L

        /** How far two fingers travel before it counts as one scroll step. */
        private const val SCROLL_STEP_PX = 12f
        private const val SCROLL_UNITS_PER_STEP = 40f
        private const val SCROLL_CLAMP = 600f

        private val MotionEvent.isFingerTouch: Boolean
            get() = source and InputDevice.SOURCE_TOUCHSCREEN == InputDevice.SOURCE_TOUCHSCREEN &&
                pointerCount > 0 && getToolType(0) == MotionEvent.TOOL_TYPE_FINGER

        /** Preserves the unsigned 16-bit wire representation in Java's `short`. */
        private fun Int.toProtocolShort(minimum: Int = 0): Short =
            coerceIn(minimum, UShort.MAX_VALUE.toInt()).toShort()
    }
}

/**
 * Decides whether releasing a gesture should emit a click.
 *
 * Duration is intentionally absent: until the delayed long-press runnable has
 * actually pressed the remote button, an unmoved release is still a tap. This
 * avoids a dead interval between a short tap and the 500 ms long press.
 */
internal fun shouldClickOnRelease(moved: Boolean, tapToClick: Boolean): Boolean =
    !moved && tapToClick
