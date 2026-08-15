package com.thor.core.display

import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies whether a secondary window follows or outlives its activity. */
class SecondaryDisplayLifecycleTest {

    @Test
    fun `standalone presentation hides when its activity stops`() {
        assertThat(
            secondaryDisplayLifecycleAction(
                event = Lifecycle.Event.ON_STOP,
                enabled = true,
                keepVisibleWhileStopped = false,
            ),
        ).isEqualTo(SecondaryDisplayLifecycleAction.HIDE)
    }

    @Test
    fun `launcher presentation remains when its activity stops`() {
        assertThat(
            secondaryDisplayLifecycleAction(
                event = Lifecycle.Event.ON_STOP,
                enabled = true,
                keepVisibleWhileStopped = true,
            ),
        ).isEqualTo(SecondaryDisplayLifecycleAction.NONE)
    }

    @Test
    fun `enabled presentation is restored when activity starts`() {
        assertThat(
            secondaryDisplayLifecycleAction(
                event = Lifecycle.Event.ON_START,
                enabled = true,
                keepVisibleWhileStopped = false,
            ),
        ).isEqualTo(SecondaryDisplayLifecycleAction.SHOW)
    }

    @Test
    fun `disabled presentation is not restored`() {
        assertThat(
            secondaryDisplayLifecycleAction(
                event = Lifecycle.Event.ON_RESUME,
                enabled = false,
                keepVisibleWhileStopped = false,
            ),
        ).isEqualTo(SecondaryDisplayLifecycleAction.NONE)
    }
}
