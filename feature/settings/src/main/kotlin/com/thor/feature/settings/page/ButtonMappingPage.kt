package com.thor.feature.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import com.thor.core.input.ControllerProfiles
import com.thor.core.input.controllerKeyLabel
import com.thor.core.model.ControllerCommand
import com.thor.core.model.ControllerProfile
import com.thor.core.model.ThorSettings
import com.thor.feature.settings.SettingsViewModel
import com.thor.feature.settings.component.RowDivider
import com.thor.feature.settings.component.row.ActionRow
import com.thor.feature.settings.component.row.ChoiceRow
import com.thor.feature.settings.component.row.InfoRow
import com.thor.feature.settings.component.row.IntSliderRow
import com.thor.feature.settings.component.row.SliderRow
import com.thor.feature.settings.component.row.TextFieldRow

/**
 * Which button does what.
 *
 * The profiles were already stored, serialised, migration-guarded and read live by
 * the activity on every settings change — two shipped and applied without a
 * restart. Nothing ever *wrote* one, so `customProfiles` was a list that could only
 * ever be empty. This page writes it.
 *
 * Binding works by borrowing the Button tester's capture mode: arm a row, and the
 * next physical press is taken as that command's button instead of being treated
 * as input. That is the only way to do it on a device whose buttons are the way
 * you are navigating the screen — a picker listing key codes would ask the user to
 * know that `KEYCODE_BUTTON_L2` is the left trigger on this pad, which is exactly
 * the thing the Button tester exists because nobody knows.
 *
 * The built-in profiles are not editable. They are the way back when a custom one
 * has been mapped into a corner, and a launcher whose defaults can be broken has
 * no way back at all — so editing one copies it first.
 */
@Composable
internal fun ButtonMappingPage(
    settings: ThorSettings,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    editingId: String?,
    awaiting: ControllerCommand?,
) {
    val controls = settings.controls
    val editing = controls.customProfiles.firstOrNull { it.id == editingId }
    if (editing == null) {
        ProfileList(controls.customProfiles, controls.activeProfileId, focusedRow, viewModel)
    } else {
        ProfileBindings(editing, focusedRow, viewModel, awaiting)
    }
}

@Composable
private fun ProfileList(
    custom: List<ControllerProfile>,
    activeId: String,
    focusedRow: Int,
    viewModel: SettingsViewModel,
) {
    val all = ControllerProfiles.BUILT_IN + custom

    InfoRow(
        "Button mapping",
        "Two profiles ship and apply the moment you pick one. Copy either to " +
            "make your own, then press a button to bind it.",
    )
    RowDivider()

    ChoiceRow(
        title = "Active profile",
        icon = Icons.Rounded.ManageAccounts,
        subtitle = "Applies immediately, everywhere",
        options = all,
        selected = all.firstOrNull { it.id == activeId } ?: ControllerProfiles.DEFAULT,
        label = ControllerProfile::name,
        focused = focusedRow == 0,
        onSelected = { profile -> viewModel.selectControllerProfile(profile.id) },
    )
    RowDivider()
    ChoiceRow(
        title = "Copy a profile",
        icon = Icons.Rounded.ContentCopy,
        subtitle = "Makes an editable copy and opens it",
        options = all,
        selected = all.firstOrNull { it.id == activeId } ?: ControllerProfiles.DEFAULT,
        label = ControllerProfile::name,
        focused = focusedRow == 1,
        onSelected = viewModel::createControllerProfile,
    )

    if (custom.isEmpty()) return

    RowDivider()
    custom.forEachIndexed { index, profile ->
        ActionRow(
            title = profile.name,
            subtitle = "${profile.bindings.size} buttons bound" +
                if (profile.id == activeId) "  ·  Active" else "",
            focused = focusedRow == PROFILE_LIST_FIRST_ROW + index,
            trailingLabel = "Edit",
            onClick = { viewModel.editControllerProfile(profile.id) },
        )
        if (index != custom.lastIndex) RowDivider()
    }
}

@Composable
private fun ProfileBindings(
    profile: ControllerProfile,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    awaiting: ControllerCommand?,
) {
    InfoRow(
        profile.name,
        awaiting?.let { "Press the button you want for \"${it.label}\"." }
            ?: "Pick a command, then press a button. This profile is applied while " +
            "you edit it, so every change is live.",
    )
    RowDivider()

    TextFieldRow(
        title = "Name",
        icon = Icons.Rounded.Badge,
        subtitle = "What this profile is called",
        value = profile.name,
        focused = focusedRow == 0,
        onValueChange = viewModel::renameEditedProfile,
    )
    RowDivider()

    ControllerCommand.entries.forEachIndexed { index, command ->
        val bound = profile.bindings
            .filterValues { it == command }
            .keys
            .map(::controllerKeyLabel)
        val armed = awaiting == command

        ActionRow(
            title = command.label,
            subtitle = when {
                armed -> "Press a button now, or press Back to leave it alone"
                bound.isEmpty() -> "${command.description}  ·  Not bound"
                else -> "${command.description}  ·  ${bound.joinToString(", ")}"
            },
            focused = focusedRow == BINDING_FIRST_ROW + index,
            trailingLabel = if (armed) "Waiting" else "Bind",
            onClick = {
                // Pressing an armed row again disarms it, so the state is
                // escapable without the pad — the pad being the thing that is
                // currently being captured.
                viewModel.armBinding(command.takeIf { !armed })
            },
        )
        RowDivider()
    }

    // ---- Feel ---------------------------------------------------------------

    IntSliderRow(
        title = "Hold to pick up",
        icon = Icons.Rounded.PanTool,
        subtitle = "How long Confirm must be held before it grabs an icon",
        value = profile.longPressMillis.toInt(),
        range = 200..1_000,
        focused = focusedRow == feelRow(0),
        suffix = "ms",
        onValueChange = { ms ->
            viewModel.updateEditedProfile { it.copy(longPressMillis = ms.toLong()) }
        },
    )
    RowDivider()
    IntSliderRow(
        title = "Repeat delay",
        icon = Icons.Rounded.Timer,
        subtitle = "How long a direction is held before it starts repeating",
        value = profile.repeatDelayMillis.toInt(),
        range = 150..800,
        focused = focusedRow == feelRow(1),
        suffix = "ms",
        onValueChange = { ms ->
            viewModel.updateEditedProfile { it.copy(repeatDelayMillis = ms.toLong()) }
        },
    )
    RowDivider()
    IntSliderRow(
        title = "Repeat speed",
        icon = Icons.Rounded.Speed,
        subtitle = "Interval between repeats once they start. Lower is faster.",
        value = profile.repeatIntervalMillis.toInt(),
        range = 40..300,
        focused = focusedRow == feelRow(2),
        suffix = "ms",
        onValueChange = { ms ->
            viewModel.updateEditedProfile { it.copy(repeatIntervalMillis = ms.toLong()) }
        },
    )
    RowDivider()
    SliderRow(
        title = "Stick dead zone",
        subtitle = "How far the stick must move before it counts as a direction",
        value = profile.stickDeadZone,
        range = 0.1f..0.9f,
        focused = focusedRow == feelRow(3),
        // Derived would be 0.1, which is eight positions across the whole usable
        // range of a stick and too coarse to fix a drifting one.
        stepOverride = DEAD_ZONE_STEP,
        valueLabel = { "${(it * 100).toInt()}%" },
        onValueChange = { zone ->
            viewModel.updateEditedProfile { it.copy(stickDeadZone = zone) }
        },
    )
    RowDivider()

    ActionRow(
        title = "Delete this profile",
        icon = Icons.Rounded.PersonRemove,
        subtitle = "Falls back to the default mapping if this one is in use",
        focused = focusedRow == feelRow(4),
        trailingLabel = "Delete",
        destructive = true,
        onClick = { viewModel.deleteControllerProfile(profile.id) },
    )
    RowDivider()
    ActionRow(
        title = "Done",
        icon = Icons.Rounded.Check,
        subtitle = "Back to the list. The profile stays applied.",
        focused = focusedRow == feelRow(5),
        trailingLabel = "Done",
        onClick = { viewModel.editControllerProfile(null) },
    )
}

/** Active profile, copy. */
private const val PROFILE_LIST_FIRST_ROW = 2

/** Name, then the commands. */
private const val BINDING_FIRST_ROW = 1

/** The feel rows and the actions come after every command. */
private fun feelRow(offset: Int): Int =
    BINDING_FIRST_ROW + ControllerCommand.entries.size + offset

/** Hold, delay, speed, dead zone, delete, done. */
private const val FEEL_AND_ACTION_ROWS = 6

internal fun buttonMappingRows(customCount: Int, editing: Boolean): Int = if (editing) {
    BINDING_FIRST_ROW + ControllerCommand.entries.size + FEEL_AND_ACTION_ROWS
} else {
    PROFILE_LIST_FIRST_ROW + customCount
}

/** Two per cent. Fine enough to dial out a drifting stick without hunting. */
private const val DEAD_ZONE_STEP = 0.02f
