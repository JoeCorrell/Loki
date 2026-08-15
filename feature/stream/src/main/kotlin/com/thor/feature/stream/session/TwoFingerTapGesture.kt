package com.thor.feature.stream.session

/** One pointer sample in panel-local pixels. */
internal data class GesturePointer(
    val id: Long,
    val x: Float,
    val y: Float,
)

/**
 * Recognises a short, stationary tap made with exactly two fingers.
 *
 * Kept independent of both `MotionEvent` and Compose pointer events so the
 * decoder view and the keyboard overlay make the same decision. A gesture is
 * rejected as soon as either finger leaves the slop radius, a third finger
 * lands, or the first finger is held past [maximumDurationMs].
 */
internal class TwoFingerTapGesture(
    private val movementSlopPx: Float = DEFAULT_MOVEMENT_SLOP_PX,
    private val maximumDurationMs: Long = DEFAULT_MAXIMUM_DURATION_MS,
) {

    private val starts = linkedMapOf<Long, GesturePointer>()
    private var startedAtMs = 0L
    private var eligible = false
    private var sawTwoFingers = false

    /** Whether child controls should stop acting on the current multi-touch gesture. */
    val interceptsGesture: Boolean
        get() = sawTwoFingers && starts.isNotEmpty()

    /** Adds a newly pressed pointer to the current gesture. */
    fun pointerDown(pointer: GesturePointer, timeMs: Long) {
        if (starts.isEmpty()) {
            startedAtMs = timeMs
            eligible = true
            sawTwoFingers = false
        }

        if (starts.putIfAbsent(pointer.id, pointer) != null) return

        when (starts.size) {
            2 -> sawTwoFingers = true
            in 3..Int.MAX_VALUE -> eligible = false
        }
    }

    /** Rejects the tap if any active pointer has travelled beyond the slop radius. */
    fun pointersMoved(pointers: Iterable<GesturePointer>) {
        val slopSquared = movementSlopPx * movementSlopPx
        pointers.forEach { pointer ->
            val start = starts[pointer.id] ?: return@forEach
            val dx = pointer.x - start.x
            val dy = pointer.y - start.y
            if (dx * dx + dy * dy >= slopSquared) eligible = false
        }
    }

    /**
     * Finishes the gesture when its first pointer lifts.
     *
     * @return true only for an eligible two-finger tap. The tracker is reset for
     *   the trailing pointer-up event before returning.
     */
    fun pointerUp(pointer: GesturePointer, timeMs: Long): Boolean {
        if (pointer.id !in starts) return false

        pointersMoved(listOf(pointer))
        val duration = timeMs - startedAtMs
        val tapped = starts.size == 2 &&
            sawTwoFingers &&
            eligible &&
            duration in 0..maximumDurationMs

        cancel()
        return tapped
    }

    /** Drops every pointer and all eligibility from an interrupted gesture. */
    fun cancel() {
        starts.clear()
        startedAtMs = 0L
        eligible = false
        sawTwoFingers = false
    }

    private companion object {
        /** Matches the stream's first scroll step, so one gesture cannot do both. */
        const val DEFAULT_MOVEMENT_SLOP_PX = 12f

        /** A deliberate tap, without making a two-finger hold toggle the overlay. */
        const val DEFAULT_MAXIMUM_DURATION_MS = 350L
    }
}
