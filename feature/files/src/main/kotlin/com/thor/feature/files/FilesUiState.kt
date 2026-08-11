package com.thor.feature.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import com.thor.core.model.Breadcrumb
import com.thor.core.model.FileEntry
import com.thor.core.model.FileSort
import com.thor.core.model.parentPathOf
import com.thor.data.files.FileShortcut
import com.thor.data.files.VolumeSpace

/**
 * Everything the explorer is showing, across both screens.
 *
 * One state, two surfaces, one cursor. The top screen browses; the bottom screen
 * describes what the cursor is on and carries the buttons that act on it. They
 * cannot disagree because there is nothing for them to disagree about — the
 * bottom panel has no cursor of its own into the listing.
 */
data class FilesUiState(
    val path: String = "",
    val crumbs: List<Breadcrumb> = emptyList(),
    val entries: List<FileEntry> = emptyList(),
    /** Index into [entries]. Meaningless when the listing is empty; see [focused]. */
    val cursor: Int = 0,
    val pane: FilesPane = FilesPane.LISTING,
    val shortcuts: List<FileShortcut> = emptyList(),
    val shortcutCursor: Int = 0,
    /** Which button on the action panel the controller is on. */
    val actionCursor: Int = 0,
    /**
     * How many buttons the action panel fitted on a row.
     *
     * Reported by the panel once it has been measured, because it is the only
     * thing that knows: the grid is sized to the width it was given. Without it
     * the pad could only walk the buttons in one dimension — Up and Down had
     * nothing to move by, and did nothing at all. The same arrangement the
     * settings screen uses for its row count, for the same reason.
     */
    val actionColumns: Int = 1,
    val actionLayout: FilesActionLayout = FilesActionLayout.GRID,
    val sort: FileSort = FileSort.NAME,
    val descending: Boolean = false,
    val showHidden: Boolean = false,
    /**
     * Paths the user has ticked, which act instead of the cursor.
     *
     * Held as paths rather than as indices or entries: a listing is re-read after
     * every change, so an index means a different file afterwards and an entry is
     * a snapshot of one that may be gone. The path is the identity — and it is
     * also what lets a mark survive walking into another folder, which is the
     * whole point of marking something.
     *
     * Insertion-ordered, so a copy lands in the order things were ticked rather
     * than in whatever order a hash happened to produce.
     */
    val marked: Set<String> = emptySet(),
    val clipboard: FileClipboard? = null,
    val status: FilesStatus = FilesStatus.Loading,
    val space: VolumeSpace? = null,
    val transfer: FileTransfer? = null,
    val prompt: FilesPrompt? = null,
    /**
     * The last thing that went *wrong*, shown until the next action clears it.
     *
     * Failures only. This carried successes too — "3 held — open a folder and
     * paste" — and the panel draws it in the error colour, so picking up three
     * files reported itself as a fault in red. What was held is already said by
     * the clipboard line, in the accent, where it belongs.
     */
    val message: String? = null,
) {
    val focused: FileEntry? get() = entries.getOrNull(cursor)

    /**
     * What an action applies to.
     *
     * Ticked files if there are any, otherwise the one under the cursor. That is
     * the rule every file manager uses, and it is worth stating once here rather
     * than at each of the six call sites that would otherwise re-derive it and
     * eventually disagree.
     */
    val targets: List<String>
        get() = if (marked.isNotEmpty()) marked.toList() else listOfNotNull(focused?.path)

    /**
     * Marked things that are not in the folder being looked at.
     *
     * Counted so the screen can say so. Marks deliberately outlive navigation —
     * ticking a file, walking somewhere else and moving it there is the reason to
     * tick anything — but that makes it possible to press Delete on a set you
     * cannot see, which is the one way this feature can go badly wrong. The number
     * is shown wherever the marks are, and the delete confirmation says it again.
     */
    val markedElsewhere: Int
        get() {
            /*
             * Counted against each mark's own parent, not against a set of the
             * listing.
             *
             * This built a `HashSet` of every path in the directory on each read,
             * and it is read from the action panel's composition — so on a folder
             * of four thousand ROMs it was four thousand inserts per recomposition,
             * for an answer that is almost always zero. Comparing the parent of
             * each *marked* path costs one string operation per tick instead, and
             * there are rarely more than a handful of those.
             */
            if (marked.isEmpty()) return 0
            return marked.count { parentPathOf(it) != path }
        }

    val focusedShortcut: FileShortcut? get() = shortcuts.getOrNull(shortcutCursor)

    /**
     * The one thing an action is about, or null when it is about several.
     *
     * Not the same as [focused], and the difference is the whole point: with one
     * file ticked and the cursor resting somewhere else, every action applies to
     * the tick — so a panel that named the cursor's file would be describing one
     * file above a Delete button aimed at another. Derived from [targets] rather
     * than alongside it, so the two cannot drift.
     */
    val subject: FileEntry?
        get() = when {
            marked.size > 1 -> null
            // Null when the single marked thing is in another folder — there is no
            // entry in view to describe, and describing the cursor instead would
            // name one file above buttons aimed at a different one.
            marked.size == 1 -> entries.firstOrNull { it.path in marked }
            else -> focused
        }

    val focusedAction: FileAction? get() = FileAction.entries.getOrNull(actionCursor)

    /**
     * Which shortcut the current directory is inside, taking the deepest.
     *
     * Every path under internal storage also begins with internal storage, so a
     * plain prefix test lights Internal storage, Downloads and Camera at once —
     * three answers to a question that has one. The longest match is the useful
     * one, and it is derived here rather than in the rail so a second surface
     * asking the same question gets the same answer.
     */
    val deepestShortcutPath: String?
        get() = shortcuts
            .map(FileShortcut::path)
            .filter { shortcut ->
                /*
                 * A share's path already ends in a slash.
                 *
                 * `smb://tower/` is written that way everywhere — jcifs will not
                 * enumerate a directory without it — so appending another produced
                 * `smb://tower//`, which matches nothing. The rail simply never lit
                 * while the user was inside a share.
                 */
                val prefix = if (shortcut.endsWith('/')) shortcut else "$shortcut/"
                path == shortcut || path.startsWith(prefix)
            }
            .maxByOrNull(String::length)

    /** True while something is happening that the user must not act on top of. */
    val isBusy: Boolean get() = transfer != null
}

/**
 * Which pane the controller is driving.
 *
 * Two of them are on the top screen and one is on the bottom, which is the whole
 * shape of this interface: browse up there, act down here.
 */
enum class FilesPane { SHORTCUTS, LISTING, ACTIONS }

/** Where a listing ended up, in the terms the screen has to draw. */
sealed interface FilesStatus {
    data object Loading : FilesStatus
    data object Ready : FilesStatus

    /** All-files access has not been granted; the screen says so and offers it. */
    data object NoAccess : FilesStatus

    data class Problem(val message: String) : FilesStatus
}

/**
 * Files held for a paste.
 *
 * A cut and a copy differ only in this flag, which is why they are one thing: the
 * difference is decided when the paste happens, not when the files are picked up,
 * and holding two clipboards would let both be non-empty at once.
 */
data class FileClipboard(val paths: List<String>, val move: Boolean) {
    val verb: String get() = if (move) "Cut" else "Copied"
    val label: String get() = if (move) "Moving" else "Copying"
}

/** A copy or a move in flight, counted in bytes. */
data class FileTransfer(
    val label: String,
    val copiedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}

/** A question the explorer is waiting on. */
sealed interface FilesPrompt {
    /** The row the cursor is on, so the pad can reach Cancel as well as Confirm. */
    val confirmFocused: Boolean

    data class Rename(
        val path: String,
        val name: String,
        override val confirmFocused: Boolean = true,
    ) : FilesPrompt

    data class NewFolder(
        val name: String,
        override val confirmFocused: Boolean = true,
    ) : FilesPrompt

    /**
     * Naming a zip before it is written.
     *
     * Asked rather than assumed, because the obvious default is wrong half the
     * time: packing six marked files has no name to take, and taking the first
     * one's would produce `Alarms.zip` holding six unrelated things.
     */
    data class Compress(
        val paths: List<String>,
        val name: String,
        override val confirmFocused: Boolean = true,
    ) : FilesPrompt

    /**
     * Deleting, which is permanent.
     *
     * Cancel is focused first, deliberately. Everything else in the launcher
     * opens on the affirmative because everything else is undoable; this is the
     * one action with no way back, so the default answer is no.
     */
    data class ConfirmDelete(
        val paths: List<String>,
        /** How many of them are not in the folder on screen; see `markedElsewhere`. */
        val elsewhere: Int = 0,
        override val confirmFocused: Boolean = false,
    ) : FilesPrompt
}

/**
 * Everything the explorer can be asked to do.
 *
 * Drawn as a permanent bar on the bottom screen rather than raised as a menu.
 * A file manager's verbs are the point of it, and hiding them behind a button
 * means every one of them costs two presses and has to be remembered first.
 */
enum class FileAction(
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    OPEN("Open", "Open with an app", Icons.Rounded.OpenInNew),
    MARK("Mark", "Mark item", Icons.Rounded.RadioButtonUnchecked),
    MARK_ALL("Select all", "Select all items", Icons.Rounded.DoneAll),
    CUT("Cut", "Move item", Icons.Rounded.ContentCut),
    COPY("Copy", "Copy item", Icons.Rounded.ContentCopy),
    PASTE("Paste", "Paste item", Icons.Rounded.ContentPaste),
    RENAME("Rename", "Rename item", Icons.Rounded.Edit),
    NEW_FOLDER("New", "New folder", Icons.Rounded.CreateNewFolder),
    COMPRESS("Zip", "Compress", Icons.Rounded.Archive),
    EXTRACT("Extract", "Extract files", Icons.Rounded.Unarchive),

    /*
     * "Delete permanently", and not "Move to trash".
     *
     * Android gives an app no shared wastebasket, and a private one would eat the
     * user's storage while telling them they had freed it. So there is no trash to
     * move anything to, and a button that said there was would be lying at exactly
     * the moment it matters most.
     */
    DELETE("Delete", "Delete permanently", Icons.Rounded.Delete),
    SORT("Sort", "Sort items", Icons.Rounded.SwapVert),

    /**
     * Last, and drawn apart from the rest.
     *
     * The others do something to a file; this changes what the list *shows*. The
     * panel renders it as a switch under its own heading rather than as a
     * thirteenth tile, and its place at the end of this list is what makes it the
     * row the pad reaches by pressing Down off the bottom of the grid.
     */
    HIDDEN("Hidden items", "Show or hide hidden files and folders", Icons.Rounded.Visibility),
}

/**
 * How the action panel lays its buttons out.
 *
 * A real preference rather than decoration: the grid fits everything on one
 * screen and is quicker to aim at with a thumb, while the list gives each verb
 * its full description and is far easier to read across a room in couch mode.
 */
enum class FilesActionLayout { GRID, LIST }

/** The verbs that go in the grid — everything except the toggle drawn beneath it. */
val GRID_ACTIONS: List<FileAction> = FileAction.entries - FileAction.HIDDEN


/**
 * Whether an action can do anything right now.
 *
 * Every action is always *drawn*, and this only greys it out. That is the
 * opposite of what a raised menu should do — there, filtering is right, because
 * the cursor has to walk past every row and a dead one is pure cost. A permanent
 * bar is the other case: buttons that appear and vanish as the cursor moves down
 * the listing make the panel flicker and move Paste under your thumb where Delete
 * was a moment ago. A fixed bar you can learn is worth a few dimmed buttons, and
 * the cursor skips them anyway — see [nextEnabledAction].
 */
fun FileAction.isEnabled(state: FilesUiState): Boolean {
    // The listing underneath a running copy is about to change, so every verb
    // here is aimed at rows that may not survive it.
    if (state.isBusy) return false

    val focused = state.focused
    val marked = state.marked.isNotEmpty()

    return when (this) {
        // A folder is entered with Confirm; "open" is for handing a file outward.
        FileAction.OPEN -> focused != null && !focused.isDirectory && !marked
        FileAction.MARK -> focused != null
        FileAction.MARK_ALL -> state.entries.isNotEmpty()
        FileAction.CUT, FileAction.COPY, FileAction.DELETE -> state.targets.isNotEmpty()
        FileAction.PASTE -> state.clipboard != null
        // One name, one field. Renaming a marked set is a different feature.
        FileAction.RENAME -> focused != null && !marked
        FileAction.NEW_FOLDER, FileAction.SORT, FileAction.HIDDEN -> true

        // Anything can be packed, including a folder and including several.
        FileAction.COMPRESS -> state.targets.isNotEmpty()

        /*
         * Zip only, and one at a time.
         *
         * `ZipInputStream` is in the platform, so it costs nothing and works
         * everywhere; 7z and rar would each be a library and a format nothing
         * else on the device could open afterwards. Offering Extract on a `.rar`
         * and then refusing is worse than not offering it.
         */
        FileAction.EXTRACT ->
            focused != null && !marked && focused.extension.equals("zip", ignoreCase = true)
    }
}

/**
 * The next action the cursor can actually land on, in the given direction.
 *
 * Returns the current index when there is nothing further, so the cursor stops at
 * the ends rather than wrapping onto a disabled button and appearing stuck.
 */
fun nextEnabledAction(state: FilesUiState, from: Int, delta: Int): Int {
    val actions = FileAction.entries
    var index = from + delta
    while (index in actions.indices) {
        if (actions[index].isEnabled(state)) return index
        index += delta
    }
    return from
}

/**
 * A vertical step through the action grid.
 *
 * Moves a whole row, then walks along that row for the nearest button that is
 * live — so Down from Copy lands under Copy where it can, and beside it where the
 * button directly below is greyed out. Falling back to a plain scan in the same
 * direction is what keeps the last row reachable when it is half empty.
 *
 * Returns the current index when the move would leave the grid, which is how the
 * caller knows Up means "leave for the listing" rather than "move".
 */
fun actionBelow(state: FilesUiState, from: Int, rows: Int): Int {
    val actions = FileAction.entries
    val columns = state.actionColumns.coerceAtLeast(1)
    val target = from + rows * columns

    if (target !in actions.indices) return from
    if (actions[target].isEnabled(state)) return target

    // Nothing directly there: take the nearest live button on the row it landed
    // on, and only then give up.
    val rowStart = target - target % columns
    val rowEnd = (rowStart + columns - 1).coerceAtMost(actions.lastIndex)
    val onRow = (rowStart..rowEnd).filter { actions[it].isEnabled(state) }
    return onRow.minByOrNull { kotlin.math.abs(it - target) } ?: from
}

/**
 * The cursor, moved and clamped.
 *
 * Clamped rather than wrapped. A grid wraps because it is a page the user knows
 * the shape of; a directory can hold ten thousand rows, and running off the
 * bottom into the top of a list that long is indistinguishable from the cursor
 * having been lost.
 */
fun moveCursor(current: Int, delta: Int, count: Int): Int =
    if (count <= 0) 0 else (current + delta).coerceIn(0, count - 1)

/**
 * Where the cursor should sit after a listing is replaced.
 *
 * Keyed on the path that was under it, so re-reading a folder after a rename or a
 * delete leaves the cursor on the same *file* rather than at the same index — an
 * index means a different row once anything above it has gone. Falls back to the
 * nearest position when that file is no longer there, which is the case that
 * matters after a delete.
 */
fun restoreCursor(entries: List<FileEntry>, wantedPath: String?, previousIndex: Int): Int {
    if (entries.isEmpty()) return 0
    val found = entries.indexOfFirst { it.path == wantedPath }
    return if (found >= 0) found else previousIndex.coerceIn(0, entries.lastIndex)
}
