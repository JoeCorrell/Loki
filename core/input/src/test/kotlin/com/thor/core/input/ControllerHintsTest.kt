package com.thor.core.input

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.thor.core.model.ControllerCommand
import com.thor.core.model.ControllerProfile
import org.junit.Test

class ControllerHintsTest {

    @Test
    fun `default profile prefers controller buttons to compatibility aliases`() {
        assertThat(ControllerProfiles.DEFAULT.controllerHint(ControllerCommand.CONFIRM).displayLabels)
            .containsExactly("A")
        assertThat(ControllerProfiles.DEFAULT.controllerHint(ControllerCommand.BACK).displayLabels)
            .containsExactly("B")
        assertThat(
            ControllerProfiles.DEFAULT.controllerHint(ControllerCommand.OPEN_SHORTCUTS).displayLabels,
        ).containsExactly("L3", "R3").inOrder()
    }

    @Test
    fun `swapped profile changes confirm back and derived pick up hints`() {
        val profile = ControllerProfiles.SWAPPED_AB

        assertThat(profile.controllerHint(ControllerCommand.CONFIRM).compactText).isEqualTo("B")
        assertThat(profile.controllerHint(ControllerCommand.BACK).compactText).isEqualTo("A")
        assertThat(profile.controllerHint(ControllerCommand.PICK_UP)).isEqualTo(
            ControllerCommandHint(displayLabels = listOf("HOLD B"), derived = true),
        )
    }

    @Test
    fun `navigation includes stick direction and best mapped controller input`() {
        val defaultHint = ControllerProfiles.DEFAULT.controllerHint(ControllerCommand.NAVIGATE_UP)
        assertThat(defaultHint.displayLabels)
            .containsExactly("Stick ↑", "D-pad ↑")
            .inOrder()

        val custom = profile(
            KeyEvent.KEYCODE_W to ControllerCommand.NAVIGATE_RIGHT,
            KeyEvent.KEYCODE_BUTTON_Y to ControllerCommand.NAVIGATE_RIGHT,
            KeyEvent.KEYCODE_DPAD_RIGHT to ControllerCommand.NAVIGATE_RIGHT,
        )
        assertThat(custom.controllerHint(ControllerCommand.NAVIGATE_RIGHT).displayLabels)
            .containsExactly("Stick →", "Y")
            .inOrder()
    }

    @Test
    fun `navigation remains reachable from the stick without a mapped key`() {
        assertThat(profile().controllerHint(ControllerCommand.NAVIGATE_LEFT).displayLabels)
            .containsExactly("Stick ←")
    }

    @Test
    fun `same-tier gamepad labels are deterministic and keyboard aliases stay hidden`() {
        val profile = profile(
            KeyEvent.KEYCODE_W to ControllerCommand.CONTEXT_MENU,
            KeyEvent.KEYCODE_BUTTON_Y to ControllerCommand.CONTEXT_MENU,
            KeyEvent.KEYCODE_BUTTON_X to ControllerCommand.CONTEXT_MENU,
        )

        assertThat(profile.controllerHint(ControllerCommand.CONTEXT_MENU).displayLabels)
            .containsExactly("X", "Y")
            .inOrder()
    }

    @Test
    fun `keyboard binding is shown when no controller binding exists`() {
        assertThat(
            profile(KeyEvent.KEYCODE_ENTER to ControllerCommand.CONFIRM)
                .controllerHint(ControllerCommand.CONFIRM)
                .compactText,
        ).isEqualTo("Enter")
    }

    @Test
    fun `unknown captured code has a safe numeric label`() {
        val unknownCode = 50_000
        assertThat(controllerKeyLabel(unknownCode)).isEqualTo("Key $unknownCode")
        assertThat(
            profile(unknownCode to ControllerCommand.SEARCH)
                .controllerHint(ControllerCommand.SEARCH)
                .compactText,
        ).isEqualTo("Key $unknownCode")
    }

    @Test
    fun `unmapped command is explicit`() {
        val hint = profile().controllerHint(ControllerCommand.TOGGLE_FAVORITE)

        assertThat(hint.isMapped).isFalse()
        assertThat(hint.displayLabels).isEmpty()
        assertThat(hint.compactText).isEqualTo(ControllerCommandHint.UNMAPPED_LABEL)
    }

    @Test
    fun `compact text bounds long lists`() {
        val hint = ControllerCommandHint(listOf("L3", "R3", "Select"))

        assertThat(hint.compact()).isEqualTo("L3 / R3 +1")
        assertThat(hint.compact(maxLabels = 1)).isEqualTo("L3 +2")
    }

    @Test
    fun `shared labels use concise stick-click names`() {
        assertThat(controllerKeyLabel(KeyEvent.KEYCODE_BUTTON_THUMBL)).isEqualTo("L3")
        assertThat(controllerKeyLabel(KeyEvent.KEYCODE_BUTTON_THUMBR)).isEqualTo("R3")
    }

    private fun profile(vararg bindings: Pair<Int, ControllerCommand>) = ControllerProfile(
        id = "test",
        name = "Test",
        bindings = mapOf(*bindings),
    )
}
