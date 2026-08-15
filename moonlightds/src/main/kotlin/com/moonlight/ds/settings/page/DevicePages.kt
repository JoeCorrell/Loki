package com.moonlight.ds.settings.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moonlight.ds.settings.SettingsViewModel
import com.moonlight.ds.settings.component.RowDivider
import com.moonlight.ds.settings.component.row.ChoiceRow
import com.moonlight.ds.settings.component.row.SliderRow
import com.moonlight.ds.settings.component.row.SwitchRow
import com.thor.core.model.DualScreenMode
import com.thor.core.model.ThorSettings

/** How many rows [DisplayPage] draws. */
internal const val DISPLAY_ROWS = 4

/** How many rows [ControllerPage] draws. */
internal const val CONTROLLER_ROWS = 5

/**
 * How the two panels are used.
 *
 * The same values Loki's dual-screen page writes, because they are the same
 * settings — this app reads `DisplaySettings.mode` and `swapScreens` through the
 * shared modules and would otherwise have no way to change them.
 *
 * Loki's version of this page carries the couch wallpaper, the home layout and
 * the split ratio for a grid that does not exist here, so what is drawn is the
 * subset that means something to a streaming app.
 */
@Composable
internal fun DisplayPage(
    settings: ThorSettings,
    focusedRow: Int,
    viewModel: SettingsViewModel,
) {
    val display = settings.display

    Column(modifier = Modifier.fillMaxWidth()) {
        ChoiceRow(
            title = "Screen mode",
            icon = Icons.Rounded.ScreenRotation,
            subtitle = "Automatic uses the second panel when the device has one. " +
                "Dual display forces it, split divides a single screen, and single " +
                "uses the bottom surface alone.",
            options = SCREEN_MODES,
            selected = display.mode.takeIf { it in SCREEN_MODES } ?: DualScreenMode.AUTO,
            focused = focusedRow == 0,
            label = { it.label },
            onSelected = { value -> viewModel.updateDisplay { it.copy(mode = value) } },
        )
        RowDivider()

        SwitchRow(
            title = "Swap the panels",
            icon = Icons.Rounded.SwapHoriz,
            subtitle = "Puts the PC list on the top screen and the details on the " +
                "bottom, for holding the device the other way up.",
            checked = display.swapScreens,
            focused = focusedRow == 1,
            onCheckedChange = { on -> viewModel.updateDisplay { it.copy(swapScreens = on) } },
        )
        RowDivider()

        SliderRow(
            title = "Split between the panels",
            subtitle = "How much of one screen the details take when both surfaces " +
                "share it. Only applies while the screen mode is split.",
            value = display.splitRatio,
            range = 0.3f..0.7f,
            steps = 7,
            focused = focusedRow == 2,
            valueLabel = { "%.0f%%".format(it * 100) },
            onValueChange = { value -> viewModel.updateDisplay { it.copy(splitRatio = value) } },
        )
        RowDivider()

        SwitchRow(
            title = "Keep the screen awake",
            icon = Icons.Rounded.Bolt,
            subtitle = "Holds the panel on while this app is in front. A stream keeps " +
                "its own screen awake regardless; this covers the time spent choosing " +
                "what to play.",
            checked = display.keepTopScreenAwake,
            focused = focusedRow == 3,
            onCheckedChange = { on ->
                viewModel.updateDisplay { it.copy(keepTopScreenAwake = on) }
            },
        )
        RowDivider()
    }
}

/**
 * The pad itself, rather than what it does inside a stream.
 *
 * The distinction matters and is why this is not on the stream controls page: the
 * rows there tune what the *PC* receives, and these tune how the pad drives this
 * app's own interface. Both are the same settings Loki writes, so a device set up
 * once behaves the same in both programs.
 *
 * Button remapping is deliberately absent. Loki's mapper is a screen of its own
 * that listens for a physical press per command, and the profiles it produces are
 * stored in the same `ControlSettings` this reads — so a profile built in Loki is
 * already in force here. Rebuilding that screen would be a second implementation
 * of the one thing that must not disagree between them.
 */
@Composable
internal fun ControllerPage(
    settings: ThorSettings,
    focusedRow: Int,
    viewModel: SettingsViewModel,
) {
    val controls = settings.controls
    val audio = settings.audio

    Column(modifier = Modifier.fillMaxWidth()) {
        SliderRow(
            title = "Stick sensitivity",
            icon = Icons.Rounded.Gamepad,
            subtitle = "Applied as an inverse dead zone, so a more sensitive stick " +
                "registers a direction at a smaller deflection. Bounded at both ends " +
                "so no setting can make the stick unusable.",
            value = controls.stickSensitivity,
            range = 0.5f..2f,
            steps = 5,
            focused = focusedRow == 0,
            valueLabel = { "%.1f×".format(it) },
            onValueChange = { value ->
                viewModel.updateControls { it.copy(stickSensitivity = value) }
            },
        )
        RowDivider()

        SwitchRow(
            title = "Wrap at the edges",
            icon = Icons.Rounded.Loop,
            subtitle = "Moving past the last PC returns to the first. Off stops dead at " +
                "each end.",
            checked = controls.wrapNavigation,
            focused = focusedRow == 1,
            onCheckedChange = { on -> viewModel.updateControls { it.copy(wrapNavigation = on) } },
        )
        RowDivider()

        SwitchRow(
            title = "Touch input",
            icon = Icons.Rounded.TouchApp,
            subtitle = "Lets a finger drive the interface as well as the pad. Turning it " +
                "off leaves the controller in sole charge, which suits the device in a " +
                "dock.",
            checked = controls.touchEnabled,
            focused = focusedRow == 2,
            onCheckedChange = { on -> viewModel.updateControls { it.copy(touchEnabled = on) } },
        )
        RowDivider()

        SwitchRow(
            title = "Haptics",
            icon = Icons.Rounded.Vibration,
            subtitle = "A short pulse as the cursor moves and when something is chosen, " +
                "including on the on-screen keyboard.",
            checked = controls.hapticsEnabled,
            focused = focusedRow == 3,
            onCheckedChange = { on -> viewModel.updateControls { it.copy(hapticsEnabled = on) } },
        )
        RowDivider()

        SwitchRow(
            title = "Interface sounds",
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            subtitle = "Navigation and confirmation sounds. Separate from the stream's " +
                "own audio, which is the PC's and is never muted by this.",
            checked = audio.soundEffectsEnabled,
            focused = focusedRow == 4,
            onCheckedChange = { on ->
                viewModel.updateAudio { it.copy(soundEffectsEnabled = on) }
            },
        )
        RowDivider()
    }
}

/**
 * The modes that mean something with no grid to lay out.
 *
 * Couch mode is absent because it is a *different interface* in Loki rather than
 * a rearrangement, and this app already draws one screen's worth of interface —
 * see `MoonlightApp`, where the television layout is chosen by the same setting
 * on the shared `StreamCouchScreen`.
 */
private val SCREEN_MODES = listOf(
    DualScreenMode.AUTO,
    DualScreenMode.DUAL_DISPLAY,
    DualScreenMode.SPLIT_SINGLE,
    DualScreenMode.SINGLE,
    DualScreenMode.COUCH,
)
