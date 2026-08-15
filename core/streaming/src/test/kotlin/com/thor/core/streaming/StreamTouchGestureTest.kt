package com.thor.core.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests touch gesture decisions without loading Android views or the native bridge. */
class StreamTouchGestureTest {

    @Test
    fun `stationary release clicks without an elapsed-time cutoff`() {
        assertThat(shouldClickOnRelease(moved = false, tapToClick = true)).isTrue()
    }

    @Test
    fun `moved gesture does not become a click`() {
        assertThat(shouldClickOnRelease(moved = true, tapToClick = true)).isFalse()
    }

    @Test
    fun `tap-to-click preference is respected`() {
        assertThat(shouldClickOnRelease(moved = false, tapToClick = false)).isFalse()
    }
}
