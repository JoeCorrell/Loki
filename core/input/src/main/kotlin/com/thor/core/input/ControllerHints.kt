package com.thor.core.input

import android.view.KeyEvent
import com.thor.core.model.ControllerCommand
import com.thor.core.model.ControllerProfile

/**
 * The physical controls that can produce one logical launcher command.
 *
 * [displayLabels] is deliberately presentation-ready: controller buttons win
 * over compatibility aliases such as Enter, and labels are stable regardless
 * of the order in which a profile was saved. An empty list means the command is
 * not reachable from this profile.
 *
 * [derived] is true when the command is synthesized from another binding. The
 * input router turns a long press of Confirm into Pick up, for example, so a
 * profile with A bound to Confirm correctly teaches "HOLD A" for Pick up.
 */
data class ControllerCommandHint(
    val displayLabels: List<String>,
    val derived: Boolean = false,
) {
    val isMapped: Boolean get() = displayLabels.isNotEmpty()

    /** A short legend suitable for a keycap row or button footer. */
    val compactText: String get() = compact()

    fun compact(maxLabels: Int = DEFAULT_COMPACT_LABELS): String {
        require(maxLabels > 0) { "maxLabels must be positive" }
        if (displayLabels.isEmpty()) return UNMAPPED_LABEL

        val visible = displayLabels.take(maxLabels).joinToString(LABEL_SEPARATOR)
        val remaining = displayLabels.size - maxLabels
        return if (remaining > 0) "$visible +$remaining" else visible
    }

    companion object {
        const val UNMAPPED_LABEL = "NOT MAPPED"
        private const val DEFAULT_COMPACT_LABELS = 2
        private const val LABEL_SEPARATOR = " / "
    }
}

/**
 * Resolves a logical command to the controls printed on the active pad.
 *
 * Profiles are many-to-one maps, and the shipped profiles also contain
 * keyboard and Android-system aliases. Showing every alias would turn a useful
 * hint such as "A" into "A / D-pad press / Enter". We therefore show every
 * binding in the highest-priority tier that exists: gamepad buttons, D-pad,
 * unknown/vendor controls, Android system controls, then keyboard keys.
 */
fun ControllerProfile.controllerHint(command: ControllerCommand): ControllerCommandHint {
    val direct = preferredBindings(command).map(::controllerKeyLabel)

    if (command.isNavigation) {
        // Stick motion is routed straight to NAVIGATE_* and does not appear in
        // ControllerProfile.bindings. It remains available even in a profile
        // with no key binding for this direction.
        val labels = listOf(command.stickLabel) + direct
        return ControllerCommandHint(labels.distinct())
    }

    if (direct.isNotEmpty()) return ControllerCommandHint(direct.distinct())

    if (command == ControllerCommand.PICK_UP) {
        val confirm = controllerHint(ControllerCommand.CONFIRM)
        return ControllerCommandHint(
            displayLabels = confirm.displayLabels.map { "HOLD $it" },
            derived = true,
        )
    }

    return ControllerCommandHint(emptyList())
}

/** A raw Android key code as the owner of a controller or keyboard names it. */
fun controllerKeyLabel(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_BUTTON_A -> "A"
    KeyEvent.KEYCODE_BUTTON_B -> "B"
    KeyEvent.KEYCODE_BUTTON_C -> "C"
    KeyEvent.KEYCODE_BUTTON_X -> "X"
    KeyEvent.KEYCODE_BUTTON_Y -> "Y"
    KeyEvent.KEYCODE_BUTTON_Z -> "Z"
    KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
    KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
    KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
    KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
    KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
    KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
    KeyEvent.KEYCODE_BUTTON_START -> "Start"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
    KeyEvent.KEYCODE_BUTTON_MODE -> "Guide"
    KeyEvent.KEYCODE_DPAD_UP -> "D-pad ↑"
    KeyEvent.KEYCODE_DPAD_DOWN -> "D-pad ↓"
    KeyEvent.KEYCODE_DPAD_LEFT -> "D-pad ←"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "D-pad →"
    KeyEvent.KEYCODE_DPAD_CENTER -> "D-pad press"
    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_ESCAPE -> "Esc"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_BACK -> "Back"
    KeyEvent.KEYCODE_MENU -> "Menu"
    KeyEvent.KEYCODE_HOME -> "Home"
    KeyEvent.KEYCODE_SEARCH -> "Search"
    KeyEvent.KEYCODE_SHIFT_LEFT -> "Left Shift"
    KeyEvent.KEYCODE_SHIFT_RIGHT -> "Right Shift"
    else -> fallbackKeyLabel(keyCode)
}

private fun ControllerProfile.preferredBindings(command: ControllerCommand): List<Int> {
    val matches = bindings
        .asSequence()
        .filter { (_, boundCommand) -> boundCommand == command }
        .map(Map.Entry<Int, ControllerCommand>::key)
        .distinct()
        .toList()
    if (matches.isEmpty()) return emptyList()

    val preferredTier = matches.minOf(::bindingTier)
    return matches
        .filter { bindingTier(it) == preferredTier }
        .sortedWith(compareBy(::bindingOrder, { it }))
}

/** Lower tiers are more useful on a controller-first handheld. */
private fun bindingTier(keyCode: Int): Int = when {
    keyCode in GAMEPAD_BUTTON_CODES -> TIER_GAMEPAD
    keyCode in DPAD_CODES -> TIER_DPAD
    keyCode in SYSTEM_CODES -> TIER_SYSTEM
    isKeyboardKey(keyCode) -> TIER_KEYBOARD
    // A captured vendor code is more likely to be an intentional controller
    // binding than a compatibility keyboard alias from the shipped profile.
    else -> TIER_VENDOR
}

private fun bindingOrder(keyCode: Int): Int = GAMEPAD_BUTTON_CODES.indexOf(keyCode)
    .takeIf { it >= 0 }
    ?: DPAD_CODES.indexOf(keyCode).takeIf { it >= 0 }?.plus(GAMEPAD_BUTTON_CODES.size)
    ?: keyCode + FALLBACK_ORDER_OFFSET

private fun isKeyboardKey(keyCode: Int): Boolean =
    keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
        keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
        keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
        keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN ||
        keyCode in KEYBOARD_CONTROL_CODES

private fun fallbackKeyLabel(keyCode: Int): String {
    val platformName = runCatching { KeyEvent.keyCodeToString(keyCode) }
        .getOrNull()
        .orEmpty()
    if (platformName.isBlank() || platformName == "KEYCODE_UNKNOWN") return "Key $keyCode"

    return platformName
        .removePrefix("KEYCODE_")
        .split('_')
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { first -> first.titlecase() }
        }
}

private val ControllerCommand.isNavigation: Boolean
    get() = this == ControllerCommand.NAVIGATE_UP ||
        this == ControllerCommand.NAVIGATE_DOWN ||
        this == ControllerCommand.NAVIGATE_LEFT ||
        this == ControllerCommand.NAVIGATE_RIGHT

private val ControllerCommand.stickLabel: String
    get() = when (this) {
        ControllerCommand.NAVIGATE_UP -> "Stick ↑"
        ControllerCommand.NAVIGATE_DOWN -> "Stick ↓"
        ControllerCommand.NAVIGATE_LEFT -> "Stick ←"
        ControllerCommand.NAVIGATE_RIGHT -> "Stick →"
        else -> error("$this is not a navigation command")
    }

private val GAMEPAD_BUTTON_CODES = listOf(
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_B,
    KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_C,
    KeyEvent.KEYCODE_BUTTON_Z,
    KeyEvent.KEYCODE_BUTTON_L1,
    KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2,
    KeyEvent.KEYCODE_BUTTON_R2,
    KeyEvent.KEYCODE_BUTTON_THUMBL,
    KeyEvent.KEYCODE_BUTTON_THUMBR,
    KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    KeyEvent.KEYCODE_BUTTON_MODE,
) + (KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16)

private val DPAD_CODES = listOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
)

private val SYSTEM_CODES = setOf(
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_SEARCH,
)

private val KEYBOARD_CONTROL_CODES = setOf(
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_ESCAPE,
    KeyEvent.KEYCODE_TAB,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_DEL,
    KeyEvent.KEYCODE_FORWARD_DEL,
    KeyEvent.KEYCODE_SHIFT_LEFT,
    KeyEvent.KEYCODE_SHIFT_RIGHT,
    KeyEvent.KEYCODE_CTRL_LEFT,
    KeyEvent.KEYCODE_CTRL_RIGHT,
    KeyEvent.KEYCODE_ALT_LEFT,
    KeyEvent.KEYCODE_ALT_RIGHT,
    KeyEvent.KEYCODE_META_LEFT,
    KeyEvent.KEYCODE_META_RIGHT,
)

private const val TIER_GAMEPAD = 0
private const val TIER_DPAD = 1
private const val TIER_VENDOR = 2
private const val TIER_SYSTEM = 3
private const val TIER_KEYBOARD = 4
private const val FALLBACK_ORDER_OFFSET = 1_000
