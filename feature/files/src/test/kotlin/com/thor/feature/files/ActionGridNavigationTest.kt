package com.thor.feature.files

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.FileEntry
import org.junit.Test

/**
 * Walking the button grid on the bottom screen.
 *
 * The panel wraps its buttons to however many rows the width allows, so "what is
 * below this one" is a question only the panel can answer — it reports its column
 * count and this arithmetic uses it. Before that existed, Up and Down had nothing
 * to move by and silently did nothing, which is precisely what it looked like.
 */
class ActionGridNavigationTest {

    private fun entry(name: String, directory: Boolean = false) = FileEntry(
        path = "/storage/$name",
        name = name,
        isDirectory = directory,
        sizeBytes = if (directory) -1L else 10L,
        modifiedEpochMs = 0L,
        isHidden = false,
        canWrite = true,
    )

    /** Everything live, so the arithmetic is being tested rather than the filter. */
    private fun state(columns: Int) = FilesUiState(
        path = "/storage",
        entries = listOf(entry("a.zip"), entry("b.txt")),
        cursor = 0,
        clipboard = FileClipboard(listOf("/storage/b.txt"), move = false),
        actionColumns = columns,
    )

    @Test
    fun `down moves a whole row`() {
        val grid = state(columns = 4)

        assertThat(actionBelow(grid, from = 1, rows = 1)).isEqualTo(5)
    }

    @Test
    fun `up moves a whole row back`() {
        val grid = state(columns = 4)

        assertThat(actionBelow(grid, from = 6, rows = -1)).isEqualTo(2)
    }

    /**
     * Off the top is how the caller learns to leave.
     *
     * Returning the current index rather than clamping is what lets Up mean
     * "back to the listing" on the first row and "move" everywhere else, without
     * the view model having to work out which row it is on.
     */
    @Test
    fun `up from the first row stays put, so the caller can leave instead`() {
        val grid = state(columns = 4)

        assertThat(actionBelow(grid, from = 2, rows = -1)).isEqualTo(2)
    }

    @Test
    fun `down off the last row stays put`() {
        val grid = state(columns = 4)
        val last = FileAction.entries.lastIndex

        assertThat(actionBelow(grid, from = last, rows = 1)).isEqualTo(last)
    }

    /** One column is a list, and the arithmetic still has to hold. */
    @Test
    fun `a single column steps one button at a time`() {
        val grid = state(columns = 1)

        assertThat(actionBelow(grid, from = 3, rows = 1)).isEqualTo(4)
        assertThat(actionBelow(grid, from = 3, rows = -1)).isEqualTo(2)
    }

    /**
     * A greyed-out button is stepped over sideways, not landed on.
     *
     * With nothing selected most verbs are dead, so a strict "one row down" would
     * put the cursor on a button that cannot be pressed — indistinguishable, from
     * the outside, from the pad having stopped working.
     */
    @Test
    fun `down lands on the nearest live button when the one below is dead`() {
        val empty = FilesUiState(path = "/storage", entries = emptyList(), actionColumns = 4)

        val landed = actionBelow(empty, from = 0, rows = 1)

        assertThat(FileAction.entries[landed].isEnabled(empty)).isTrue()
    }

    @Test
    fun `a row with nothing live on it is not landed on at all`() {
        val empty = FilesUiState(path = "/storage", entries = emptyList(), actionColumns = 3)

        // Row 1 of a 3-wide grid is MARK_ALL, CUT, COPY — all dead with no files.
        assertThat(actionBelow(empty, from = 0, rows = 1)).isEqualTo(0)
    }

    // ---- What the archive verbs apply to ------------------------------------

    @Test
    fun `extract is offered for a zip and nothing else`() {
        val onZip = state(columns = 4).copy(cursor = 0)
        val onText = state(columns = 4).copy(cursor = 1)

        assertThat(FileAction.EXTRACT.isEnabled(onZip)).isTrue()
        assertThat(FileAction.EXTRACT.isEnabled(onText)).isFalse()
    }

    /** Extracting six archives at once is a different feature, not this one repeated. */
    @Test
    fun `extract goes dead once several things are marked`() {
        val marked = state(columns = 4).copy(marked = setOf("/storage/a.zip", "/storage/b.txt"))

        assertThat(FileAction.EXTRACT.isEnabled(marked)).isFalse()
    }

    @Test
    fun `anything can be packed, including several things at once`() {
        val marked = state(columns = 4).copy(marked = setOf("/storage/a.zip", "/storage/b.txt"))

        assertThat(FileAction.COMPRESS.isEnabled(marked)).isTrue()
        assertThat(FileAction.COMPRESS.isEnabled(state(columns = 4))).isTrue()
    }

    @Test
    fun `nothing can be packed in an empty folder`() {
        val empty = FilesUiState(path = "/storage", entries = emptyList())

        assertThat(FileAction.COMPRESS.isEnabled(empty)).isFalse()
    }
}
