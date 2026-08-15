package com.thor.feature.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LauncherIntroGateTest {

    @Test
    fun `only the first view model in a process receives the intro`() {
        val gate = LauncherIntroGate()

        assertThat(gate.claim()).isTrue()
        assertThat(gate.claim()).isFalse()
        assertThat(gate.claim()).isFalse()
    }

    @Test
    fun `a new process scoped gate receives a new intro`() {
        val previousProcess = LauncherIntroGate()
        val newProcess = LauncherIntroGate()

        assertThat(previousProcess.claim()).isTrue()
        assertThat(previousProcess.claim()).isFalse()
        assertThat(newProcess.claim()).isTrue()
    }
}
