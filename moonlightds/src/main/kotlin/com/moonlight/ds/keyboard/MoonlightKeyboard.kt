package com.moonlight.ds.keyboard

import androidx.compose.runtime.Immutable
import com.thor.core.common.clipboard.ThorClipboard
import com.thor.core.model.ControllerCommand
import com.thor.core.model.KeyboardKey
import com.thor.core.model.KeyboardLayer
import com.thor.core.model.NavDirection
import com.thor.core.model.ThorKeyboardLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State for the on-screen keyboard.
 *
 * The keyboard itself is shared — [com.thor.core.ui.component.ThorKeyboard] draws
 * it, [ThorKeyboardLayout] defines its keys, and both come from the same modules
 * Loki uses, so the thing on screen is the same keyboard down to the key
 * positions. What is here is only the small state machine that says which key the
 * cursor is on and what has been typed so far.
 *
 * That is app-local rather than shared because it is bound up with the shell that
 * hosts it: Loki's copy lives on its launcher view model and closes the app
 * drawer and the side menu on the way up, neither of which exists here. Sharing
 * it would mean parameterising all of that away for one caller.
 */
@Immutable
data class MoonlightKeyboardState(
    val visible: Boolean = false,
    val text: String = "",
    /** What the text is for, shown above the field. */
    val label: String = "",
    val layer: KeyboardLayer = KeyboardLayer.LETTERS,
    /** Latched for a single character, as a phone keyboard's shift is. */
    val shifted: Boolean = false,
    val cursorRow: Int = 1,
    val cursorColumn: Int = 0,
    /** The clipboard sheet, raised over the keys by the clipboard key. */
    val clipboardOpen: Boolean = false,
    /** Clips offered by the sheet, most recent first. */
    val clips: List<String> = emptyList(),
    val clipIndex: Int = 0,
)

/**
 * Drives [MoonlightKeyboardState] from taps and from the controller.
 *
 * One entry point for both, so a shift latched by the Y button shows on the key a
 * thumb is about to press and a cursor moved by the D-pad is the same cursor a tap
 * sets. Deliberately not a view model: the session window needs a keyboard too and
 * is a plain activity, so this is an ordinary object either can own.
 */
class MoonlightKeyboard(
    private val clipboard: ThorClipboard,
    /** Raised when the user finishes, so the shell can put the cursor back. */
    private val onDone: () -> Unit = {},
) {

    private val _state = MutableStateFlow(MoonlightKeyboardState())
    val state: StateFlow<MoonlightKeyboardState> = _state.asStateFlow()

    val visible: Boolean get() = _state.value.visible

    /** Raises the keyboard, filling in [label] with [initial]. */
    fun open(label: String = "Text", initial: String = "") {
        _state.value = MoonlightKeyboardState(visible = true, label = label, text = initial)
    }

    fun close() {
        _state.update { it.copy(visible = false, clipboardOpen = false, clips = emptyList()) }
        onDone()
    }

    /**
     * Applies a key.
     *
     * Editing is append-and-backspace rather than a full caret model. What gets
     * typed here is an address or a machine name, and a caret the user would have
     * to drive with a stick is more machinery than the job needs.
     */
    fun onKey(key: KeyboardKey) {
        when (key) {
            is KeyboardKey.Character -> _state.update { state ->
                state.copy(
                    text = state.text + key.resolve(state.shifted),
                    // Latched, not held: shift applies to one character and
                    // releases, which is the only behaviour that works when shift
                    // is a button press rather than something a finger holds down.
                    shifted = false,
                )
            }

            KeyboardKey.Space -> _state.update { it.copy(text = it.text + ' ') }

            KeyboardKey.Backspace -> _state.update { it.copy(text = it.text.dropLast(1)) }

            KeyboardKey.Shift -> _state.update { it.copy(shifted = !it.shifted) }

            KeyboardKey.Clipboard -> toggleClipboardSheet()

            KeyboardKey.Layer -> _state.update { state ->
                val next = when (state.layer) {
                    KeyboardLayer.LETTERS -> KeyboardLayer.SYMBOLS
                    KeyboardLayer.SYMBOLS -> KeyboardLayer.LETTERS
                }
                // Re-clamped through the layout, because the arriving layer's rows
                // are not the same lengths as the one being left.
                val cursor = ThorKeyboardLayout.move(
                    layer = next,
                    row = state.cursorRow,
                    column = state.cursorColumn,
                    // A no-op direction: LEFT from column 0 stays put, and every
                    // other position is clamped on the way through.
                    direction = NavDirection.LEFT,
                )
                state.copy(layer = next, cursorRow = cursor.row, cursorColumn = cursor.column)
            }

            // Done and close are the same thing: every edit has already been
            // applied to the field as it was typed, so there is nothing to commit.
            KeyboardKey.Enter, KeyboardKey.Cancel -> close()
        }
    }

    /**
     * Controller input while the keyboard is up.
     *
     * Returns true when the press was consumed, which is always while the keyboard
     * is visible — a direction that fell through to the shell would move the PC
     * list under the keyboard the user is typing on.
     */
    fun handleCommand(command: ControllerCommand): Boolean {
        if (!_state.value.visible) return false

        val state = _state.value
        if (state.clipboardOpen) {
            handleClipboardCommand(command)
            return true
        }

        when (command) {
            ControllerCommand.NAVIGATE_UP -> moveCursor(NavDirection.UP)
            ControllerCommand.NAVIGATE_DOWN -> moveCursor(NavDirection.DOWN)
            ControllerCommand.NAVIGATE_LEFT -> moveCursor(NavDirection.LEFT)
            ControllerCommand.NAVIGATE_RIGHT -> moveCursor(NavDirection.RIGHT)

            ControllerCommand.CONFIRM -> ThorKeyboardLayout
                .keyAt(state.layer, state.cursorRow, state.cursorColumn)
                ?.let(::onKey)

            // Back deletes when there is something to delete and closes when there
            // is not, so the button never traps the user on a surface they cannot
            // leave and never throws away an address in one press.
            ControllerCommand.BACK -> if (state.text.isEmpty()) {
                onKey(KeyboardKey.Cancel)
            } else {
                onKey(KeyboardKey.Backspace)
            }

            // The face buttons double as the keys a typist reaches for most.
            ControllerCommand.TOGGLE_FAVORITE -> onKey(KeyboardKey.Space)
            ControllerCommand.CONTEXT_MENU -> onKey(KeyboardKey.Shift)

            ControllerCommand.PAGE_PREVIOUS,
            ControllerCommand.PAGE_NEXT,
            -> onKey(KeyboardKey.Layer)

            ControllerCommand.OPEN_SIDE_MENU -> onKey(KeyboardKey.Enter)

            else -> Unit
        }
        return true
    }

    /**
     * Opens or closes the clipboard sheet.
     *
     * The clip is read on the way in rather than watched. Android only lets the
     * focused app read the clipboard, and this app is focused exactly now —
     * pressing the key is the moment it is both allowed and worth doing.
     */
    fun toggleClipboardSheet() {
        val open = !_state.value.clipboardOpen
        if (open) clipboard.refresh()
        _state.update { state ->
            state.copy(
                clipboardOpen = open,
                clips = if (open) clipboard.history.value else emptyList(),
                clipIndex = 0,
            )
        }
    }

    /** Inserts a clip at the caret and closes the sheet. */
    fun pasteClip(text: String) {
        _state.update { state ->
            state.copy(
                text = state.text + text,
                clipboardOpen = false,
                clips = emptyList(),
            )
        }
    }

    /** Copies what is in the field onto the system clipboard. */
    fun copyFieldText() {
        val text = _state.value.text
        if (text.isBlank()) return
        clipboard.copy(text)
        _state.update { it.copy(clipboardOpen = false, clips = emptyList()) }
    }

    /**
     * Controller input while the clipboard sheet is up.
     *
     * Handled before the keys, because while the sheet is open the keyboard
     * underneath must not also be typing what the user is scrolling past.
     */
    private fun handleClipboardCommand(command: ControllerCommand) {
        val state = _state.value
        // One past the clips is the "copy this field" row, which is why the sheet
        // is navigable even with nothing on the clipboard.
        val lastIndex = state.clips.size
        when (command) {
            ControllerCommand.NAVIGATE_UP -> _state.update {
                it.copy(clipIndex = (it.clipIndex - 1).coerceAtLeast(0))
            }

            ControllerCommand.NAVIGATE_DOWN -> _state.update {
                it.copy(clipIndex = (it.clipIndex + 1).coerceAtMost(lastIndex))
            }

            ControllerCommand.CONFIRM -> {
                val clip = state.clips.getOrNull(state.clipIndex)
                if (clip != null) pasteClip(clip) else copyFieldText()
            }

            ControllerCommand.BACK -> _state.update {
                it.copy(clipboardOpen = false, clips = emptyList())
            }

            else -> Unit
        }
    }

    private fun moveCursor(direction: NavDirection) {
        _state.update { state ->
            val cursor = ThorKeyboardLayout.move(
                layer = state.layer,
                row = state.cursorRow,
                column = state.cursorColumn,
                direction = direction,
            )
            state.copy(cursorRow = cursor.row, cursorColumn = cursor.column)
        }
    }
}
