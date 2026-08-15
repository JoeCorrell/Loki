package com.thor.feature.settings.tutorial

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TutorialLayoutTest {

    @Test
    fun `Thor upper panel uses wide layout`() {
        assertThat(tutorialLayoutMode(widthDp = 1_144f, heightDp = 614f))
            .isEqualTo(TutorialLayoutMode.WIDE)
    }

    @Test
    fun `Thor lower panel uses compact layout`() {
        assertThat(tutorialLayoutMode(widthDp = 705f, heightDp = 614f))
            .isEqualTo(TutorialLayoutMode.COMPACT)
    }

    @Test
    fun `short window stays compact even when wide`() {
        assertThat(tutorialLayoutMode(widthDp = 1_200f, heightDp = 540f))
            .isEqualTo(TutorialLayoutMode.COMPACT)
    }

    @Test
    fun `breakpoint is inclusive`() {
        assertThat(tutorialLayoutMode(WIDE_MIN_WIDTH_DP, WIDE_MIN_HEIGHT_DP))
            .isEqualTo(TutorialLayoutMode.WIDE)
    }
}
