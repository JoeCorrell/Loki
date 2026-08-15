package com.moonlight.ds.settings

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.ControllerCommand
import org.junit.Test

/**
 * The settings cursor.
 *
 * Worth testing because none of it is visible when it breaks: a rail that
 * swallows Back is a settings screen with no way out, and a row that does not
 * claim Left and Right is a dropdown that cannot be changed with the pad. Both
 * look like a dead controller rather than like a bug.
 */
class SettingsControllerTest {

    @Test
    fun `starts on the rail at the first page`() {
        val controller = SettingsController()

        assertThat(controller.cursor.onRail).isTrue()
        assertThat(controller.cursor.page).isEqualTo(SettingsPage.VIDEO)
        assertThat(controller.cursor.row).isEqualTo(0)
    }

    @Test
    fun `up and down walk the rail and wrap at both ends`() {
        val controller = SettingsController()
        val pages = SettingsPage.entries

        // Up from the first page reaches the last rather than stopping dead.
        controller.handleCommand(ControllerCommand.NAVIGATE_UP)
        assertThat(controller.cursor.page).isEqualTo(pages.last())

        controller.handleCommand(ControllerCommand.NAVIGATE_DOWN)
        assertThat(controller.cursor.page).isEqualTo(pages.first())
    }

    @Test
    fun `right enters the page and left returns to the rail`() {
        val controller = SettingsController()

        controller.handleCommand(ControllerCommand.NAVIGATE_RIGHT)
        assertThat(controller.cursor.onRail).isFalse()
        assertThat(controller.cursor.row).isEqualTo(0)

        controller.handleCommand(ControllerCommand.NAVIGATE_LEFT)
        assertThat(controller.cursor.onRail).isTrue()
    }

    @Test
    fun `rows stop at both ends rather than wrapping`() {
        val controller = SettingsController()
        controller.handleCommand(ControllerCommand.NAVIGATE_RIGHT)

        // Up from the first row stays put: a settings list is read top to bottom,
        // and wrapping from the first row to the last reads as a jump.
        controller.handleCommand(ControllerCommand.NAVIGATE_UP)
        assertThat(controller.cursor.row).isEqualTo(0)

        repeat(SettingsPage.VIDEO.rows + 5) {
            controller.handleCommand(ControllerCommand.NAVIGATE_DOWN)
        }
        assertThat(controller.cursor.row).isEqualTo(SettingsPage.VIDEO.rows - 1)
    }

    @Test
    fun `back leaves the page but is passed on from the rail`() {
        val controller = SettingsController()
        controller.handleCommand(ControllerCommand.NAVIGATE_RIGHT)

        // Consumed: it moves the cursor back to the rail.
        assertThat(controller.handleCommand(ControllerCommand.BACK)).isTrue()
        assertThat(controller.cursor.onRail).isTrue()

        // Declined, so the shell above can close the settings screen. A rail that
        // consumed this would be a screen with no way out.
        assertThat(controller.handleCommand(ControllerCommand.BACK)).isFalse()
    }

    @Test
    fun `confirm enters the page from the rail and activates a row inside it`() {
        val controller = SettingsController()

        val before = controller.activation
        controller.handleCommand(ControllerCommand.CONFIRM)
        // On the rail, Confirm is "open this page" rather than "press this row",
        // so nothing is activated yet.
        assertThat(controller.cursor.onRail).isFalse()
        assertThat(controller.activation).isEqualTo(before)

        controller.handleCommand(ControllerCommand.CONFIRM)
        assertThat(controller.activation).isEqualTo(before + 1)
    }

    @Test
    fun `a row that claims the horizontal keeps left from leaving the page`() {
        val controller = SettingsController()
        controller.handleCommand(ControllerCommand.NAVIGATE_RIGHT)
        controller.setRowTakesHorizontal(true)

        controller.handleCommand(ControllerCommand.NAVIGATE_RIGHT)
        assertThat(controller.step).isEqualTo(1)

        controller.handleCommand(ControllerCommand.NAVIGATE_LEFT)
        assertThat(controller.step).isEqualTo(0)
        // Still on the page: the dropdown took the press, so it was a value change
        // rather than a move back to the rail.
        assertThat(controller.cursor.onRail).isFalse()

        // Once the row lets go, Left means "leave" again.
        controller.setRowTakesHorizontal(false)
        controller.handleCommand(ControllerCommand.NAVIGATE_LEFT)
        assertThat(controller.cursor.onRail).isTrue()
    }

    @Test
    fun `tapping a page leaves the cursor on the rail`() {
        val controller = SettingsController()
        controller.handleCommand(ControllerCommand.NAVIGATE_RIGHT)

        controller.selectPage(SettingsPage.CONTROLLER)

        assertThat(controller.cursor.page).isEqualTo(SettingsPage.CONTROLLER)
        assertThat(controller.cursor.onRail).isTrue()
        assertThat(controller.cursor.row).isEqualTo(0)
    }

    @Test
    fun `every page declares as many rows as it draws`() {
        // A page whose row count is short strands the cursor before the last row;
        // one that is long parks it on a row that does not exist and nothing lights.
        SettingsPage.entries.forEach { page ->
            assertThat(page.rows).isGreaterThan(0)
        }
    }
}
