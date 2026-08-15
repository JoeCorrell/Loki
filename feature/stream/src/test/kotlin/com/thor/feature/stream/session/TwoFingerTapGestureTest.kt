package com.thor.feature.stream.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests the gesture shared by the decoder view and keyboard overlay. */
class TwoFingerTapGestureTest {

    @Test
    fun `two stationary fingers toggle on the first release`() {
        val gesture = TwoFingerTapGesture()

        gesture.pointerDown(pointer(id = 1, x = 40f, y = 50f), timeMs = 10L)
        gesture.pointerDown(pointer(id = 2, x = 90f, y = 50f), timeMs = 30L)
        gesture.pointersMoved(
            listOf(
                pointer(id = 1, x = 42f, y = 51f),
                pointer(id = 2, x = 91f, y = 52f),
            ),
        )

        assertThat(gesture.interceptsGesture).isTrue()
        assertThat(
            gesture.pointerUp(pointer(id = 2, x = 91f, y = 52f), timeMs = 120L),
        ).isTrue()
        assertThat(gesture.interceptsGesture).isFalse()
    }

    @Test
    fun `two-finger movement remains a scroll rather than a tap`() {
        val gesture = TwoFingerTapGesture()

        gesture.pointerDown(pointer(id = 1, x = 40f, y = 50f), timeMs = 10L)
        gesture.pointerDown(pointer(id = 2, x = 90f, y = 50f), timeMs = 20L)
        gesture.pointersMoved(
            listOf(
                pointer(id = 1, x = 40f, y = 86f),
                pointer(id = 2, x = 90f, y = 86f),
            ),
        )

        assertThat(
            gesture.pointerUp(pointer(id = 1, x = 40f, y = 86f), timeMs = 100L),
        ).isFalse()
    }

    @Test
    fun `movement at the first scroll step cannot also toggle`() {
        val gesture = TwoFingerTapGesture(movementSlopPx = 12f)

        gesture.pointerDown(pointer(id = 1, x = 40f, y = 50f), timeMs = 10L)
        gesture.pointerDown(pointer(id = 2, x = 90f, y = 50f), timeMs = 20L)
        gesture.pointersMoved(
            listOf(
                pointer(id = 1, x = 40f, y = 62f),
                pointer(id = 2, x = 90f, y = 62f),
            ),
        )

        assertThat(
            gesture.pointerUp(pointer(id = 1, x = 40f, y = 62f), timeMs = 100L),
        ).isFalse()
    }

    @Test
    fun `movement before the second finger also rejects the tap`() {
        val gesture = TwoFingerTapGesture()

        gesture.pointerDown(pointer(id = 1, x = 10f, y = 10f), timeMs = 0L)
        gesture.pointersMoved(listOf(pointer(id = 1, x = 45f, y = 10f)))
        gesture.pointerDown(pointer(id = 2, x = 80f, y = 10f), timeMs = 50L)

        assertThat(
            gesture.pointerUp(pointer(id = 2, x = 80f, y = 10f), timeMs = 100L),
        ).isFalse()
    }

    @Test
    fun `a hold and a third finger do not toggle`() {
        val held = TwoFingerTapGesture(maximumDurationMs = 100L)
        held.pointerDown(pointer(id = 1), timeMs = 0L)
        held.pointerDown(pointer(id = 2), timeMs = 10L)
        assertThat(held.pointerUp(pointer(id = 1), timeMs = 101L)).isFalse()

        val crowded = TwoFingerTapGesture()
        crowded.pointerDown(pointer(id = 1), timeMs = 0L)
        crowded.pointerDown(pointer(id = 2), timeMs = 10L)
        crowded.pointerDown(pointer(id = 3), timeMs = 20L)
        assertThat(crowded.pointerUp(pointer(id = 3), timeMs = 30L)).isFalse()
    }

    @Test
    fun `cancelled gesture does not poison the next tap`() {
        val gesture = TwoFingerTapGesture()
        gesture.pointerDown(pointer(id = 1), timeMs = 0L)
        gesture.pointerDown(pointer(id = 2), timeMs = 10L)
        gesture.cancel()

        gesture.pointerDown(pointer(id = 3), timeMs = 100L)
        gesture.pointerDown(pointer(id = 4), timeMs = 110L)

        assertThat(gesture.pointerUp(pointer(id = 3), timeMs = 150L)).isTrue()
    }

    private fun pointer(
        id: Long,
        x: Float = 0f,
        y: Float = 0f,
    ) = GesturePointer(id = id, x = x, y = y)
}
