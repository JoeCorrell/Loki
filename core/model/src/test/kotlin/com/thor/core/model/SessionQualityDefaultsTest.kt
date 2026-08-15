package com.thor.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Verifies that a fresh Thor streaming profile enables its defining input/display features. */
class SessionQualityDefaultsTest {

    @Test
    fun `fresh profile requests dual display and direct touch`() {
        val quality = SessionQuality()

        assertThat(quality.secondDisplay).isTrue()
        assertThat(quality.touchVideoAsPointer).isTrue()
    }
}
