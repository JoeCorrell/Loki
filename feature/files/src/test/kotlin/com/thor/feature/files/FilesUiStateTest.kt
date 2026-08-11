package com.thor.feature.files

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.FileEntry
import com.thor.data.files.FileShortcut
import org.junit.Test

/**
 * The rules the explorer's cursor and menu follow.
 *
 * These are the parts that decide what an action *applies to*, which is the one
 * thing a file manager cannot get wrong quietly: a delete aimed at the wrong set
 * is not a rendering fault, it is missing files.
 */
class FilesUiStateTest {

    private fun entry(name: String, directory: Boolean = false) = FileEntry(
        path = "/storage/$name",
        name = name,
        isDirectory = directory,
        sizeBytes = if (directory) -1L else 10L,
        modifiedEpochMs = 0L,
        isHidden = false,
        canWrite = true,
    )

    private fun state(
        entries: List<FileEntry> = listOf(entry("a.txt"), entry("b.txt"), entry("dir", true)),
        cursor: Int = 0,
        marked: Set<String> = emptySet(),
        clipboard: FileClipboard? = null,
    ) = FilesUiState(
        path = "/storage",
        entries = entries,
        cursor = cursor,
        marked = marked,
        clipboard = clipboard,
    )

    // ---- What an action applies to -----------------------------------------

    @Test
    fun `with nothing marked an action applies to the cursor`() {
        val targets = state(cursor = 1).targets

        assertThat(targets).containsExactly("/storage/b.txt")
    }

    /** With one mark in another folder there is no entry in view to describe. */
    @Test
    fun `the subject is nothing when the only mark is elsewhere`() {
        assertThat(state(marked = setOf("/elsewhere/rom.sfc")).subject).isNull()
    }

    @Test
    fun `marks outrank the cursor`() {
        val targets = state(
            cursor = 0,
            marked = setOf("/storage/b.txt", "/storage/dir"),
        ).targets

        assertThat(targets).containsExactly("/storage/b.txt", "/storage/dir")
    }

    /**
     * A mark on something in another folder still counts.
     *
     * This is the whole point of marks outliving navigation: tick some files,
     * walk to where they belong, move them. An earlier version pruned the targets
     * against the current listing, which meant the set was silently empty by the
     * time the user arrived at the destination — the feature looked like it
     * worked and did nothing.
     */
    @Test
    fun `a mark somewhere else is still a target`() {
        val targets = state(marked = setOf("/elsewhere/rom.sfc")).targets

        assertThat(targets).containsExactly("/elsewhere/rom.sfc")
    }

    /** And the screen can say how many of them are out of sight. */
    @Test
    fun `marks outside this folder are counted`() {
        val current = state(marked = setOf("/storage/a.txt", "/elsewhere/rom.sfc"))

        assertThat(current.markedElsewhere).isEqualTo(1)
        assertThat(state(marked = setOf("/storage/a.txt")).markedElsewhere).isEqualTo(0)
    }

    /** Ticking order is copy order, which a hash set would not have preserved. */
    @Test
    fun `marks are returned in the order they were ticked`() {
        val targets = state(
            marked = linkedSetOf("/storage/dir", "/storage/a.txt"),
        ).targets

        assertThat(targets).containsExactly("/storage/dir", "/storage/a.txt").inOrder()
    }

    // ---- The action bar ----------------------------------------------------

    @Test
    fun `paste is live only when something is held`() {
        assertThat(FileAction.PASTE.isEnabled(state())).isFalse()

        val holding = state(clipboard = FileClipboard(listOf("/storage/a.txt"), move = false))
        assertThat(FileAction.PASTE.isEnabled(holding)).isTrue()
    }

    /** One name, one field — renaming a marked set is a different feature. */
    @Test
    fun `rename goes dead once several things are marked`() {
        val marked = state(marked = setOf("/storage/a.txt", "/storage/b.txt"))

        assertThat(FileAction.RENAME.isEnabled(marked)).isFalse()
    }

    /** A folder is entered with Confirm; Open is for handing a file outward. */
    @Test
    fun `a folder cannot be opened with another app`() {
        val onFolder = state(cursor = 2)

        assertThat(onFolder.focused?.isDirectory).isTrue()
        assertThat(FileAction.OPEN.isEnabled(onFolder)).isFalse()
    }

    /**
     * An empty folder can still be added to.
     *
     * A bar with nothing live on it leaves the user with no way to create the
     * first thing in an empty directory.
     */
    @Test
    fun `an empty folder still offers the actions that need no file`() {
        val empty = state(entries = emptyList())

        assertThat(FileAction.NEW_FOLDER.isEnabled(empty)).isTrue()
        assertThat(FileAction.DELETE.isEnabled(empty)).isFalse()
        assertThat(FileAction.MARK.isEnabled(empty)).isFalse()
    }

    /**
     * There is always somewhere for the cursor to be.
     *
     * The bar is drawn whole and only greyed, so the layout never reflows as the
     * listing cursor moves — but that guarantee is worth nothing if every button
     * can go dead at once. Three verbs are unconditional, which is what makes an
     * empty unreadable folder still an operable screen rather than a dead end.
     */
    @Test
    fun `some actions are live in every state there is`() {
        val everyState = listOf(
            state(),
            state(entries = emptyList()),
            state(cursor = 2),
            state(marked = setOf("/storage/a.txt", "/storage/b.txt")),
            state(clipboard = FileClipboard(listOf("/storage/a.txt"), move = true)),
        )

        everyState.forEach { current ->
            assertThat(FileAction.NEW_FOLDER.isEnabled(current)).isTrue()
            assertThat(FileAction.SORT.isEnabled(current)).isTrue()
            assertThat(FileAction.HIDDEN.isEnabled(current)).isTrue()
            assertThat(FileAction.entries.any { it.isEnabled(current) }).isTrue()
        }
    }

    /** Walking the bar never rests on a button that would do nothing. */
    @Test
    fun `the action cursor skips over the dead buttons`() {
        val empty = state(entries = emptyList())

        var index = 0
        repeat(FileAction.entries.size) {
            val next = nextEnabledAction(empty, from = index, delta = 1)
            assertThat(FileAction.entries[next].isEnabled(empty)).isTrue()
            index = next
        }
    }

    @Test
    fun `the action cursor stops rather than landing on a dead button`() {
        val empty = state(entries = emptyList())
        val last = FileAction.entries.lastIndex

        // SORT and HIDDEN are always live, so walking right from the end stays put.
        assertThat(nextEnabledAction(empty, from = last, delta = 1)).isEqualTo(last)
    }

    // ---- The cursor --------------------------------------------------------

    @Test
    fun `the cursor stops at both ends rather than wrapping`() {
        assertThat(moveCursor(current = 0, delta = -1, count = 5)).isEqualTo(0)
        assertThat(moveCursor(current = 4, delta = 1, count = 5)).isEqualTo(4)
    }

    @Test
    fun `an accelerated step past the end lands on the end`() {
        assertThat(moveCursor(current = 3, delta = 12, count = 5)).isEqualTo(4)
    }

    @Test
    fun `an empty listing has no cursor to move`() {
        assertThat(moveCursor(current = 0, delta = 1, count = 0)).isEqualTo(0)
    }

    /** Re-reading a folder leaves the cursor on the same *file*, not the same row. */
    @Test
    fun `the cursor follows its file across a re-read`() {
        val reordered = listOf(entry("dir", true), entry("a.txt"), entry("b.txt"))

        val restored = restoreCursor(reordered, wantedPath = "/storage/b.txt", previousIndex = 0)

        assertThat(restored).isEqualTo(2)
    }

    /**
     * When that file has gone, the cursor holds its position.
     *
     * This is the case after a delete, and the alternative — jumping to the top —
     * loses the user's place in a folder they were part-way down.
     */
    @Test
    fun `a deleted file leaves the cursor where it was`() {
        val remaining = listOf(entry("a.txt"), entry("b.txt"))

        val restored = restoreCursor(remaining, wantedPath = "/storage/gone", previousIndex = 1)

        assertThat(restored).isEqualTo(1)
    }

    @Test
    fun `the cursor is clamped when the folder has shrunk beneath it`() {
        val remaining = listOf(entry("a.txt"))

        val restored = restoreCursor(remaining, wantedPath = null, previousIndex = 7)

        assertThat(restored).isEqualTo(0)
    }

    // ---- Which rail entry is lit -------------------------------------------

    private val rail = listOf(
        FileShortcut("Internal storage", "/storage/emulated/0"),
        FileShortcut("Downloads", "/storage/emulated/0/Download"),
        FileShortcut("Tower", "smb://tower/", remote = true),
    )

    /**
     * Every path under internal storage also begins with internal storage, so a
     * plain prefix test lights two rows for a question that has one answer.
     */
    @Test
    fun `the deepest matching shortcut wins`() {
        val state = FilesUiState(path = "/storage/emulated/0/Download/roms", shortcuts = rail)

        assertThat(state.deepestShortcutPath).isEqualTo("/storage/emulated/0/Download")
    }

    /**
     * A share's path already ends in a slash, and appending another produced
     * `smb://tower//` — which matched nothing, so the rail never lit inside a
     * share at all.
     */
    @Test
    fun `a folder on a share lights its server`() {
        val state = FilesUiState(path = "smb://tower/games/snes/", shortcuts = rail)

        assertThat(state.deepestShortcutPath).isEqualTo("smb://tower/")
    }

    /** And standing on the server's own root lights it too. */
    @Test
    fun `a share root lights its own row`() {
        val state = FilesUiState(path = "smb://tower/", shortcuts = rail)

        assertThat(state.deepestShortcutPath).isEqualTo("smb://tower/")
    }

    /** A different server is not this one, however alike the two names look. */
    @Test
    fun `another server does not light this one`() {
        val state = FilesUiState(path = "smb://tower-two/games/", shortcuts = rail)

        assertThat(state.deepestShortcutPath).isNull()
    }
}
