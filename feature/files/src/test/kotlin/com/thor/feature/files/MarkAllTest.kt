package com.thor.feature.files

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.FileEntry
import org.junit.Test

/**
 * Ticking a whole folder when marks outlive the folder they were made in.
 *
 * The two features interact, and the interaction is where this went wrong: with
 * anything ticked elsewhere, "Select all" compared the whole mark set against one
 * directory's contents, never matched, and replaced the lot — quietly discarding
 * the marks somebody had walked here to add to.
 */
class MarkAllTest {

    private fun entry(name: String) = FileEntry(
        path = "/storage/$name",
        name = name,
        isDirectory = false,
        sizeBytes = 1L,
        modifiedEpochMs = 0L,
        isHidden = false,
        canWrite = true,
    )

    private val here = listOf(entry("a.txt"), entry("b.txt"))

    /** What the view model does, expressed where a test can reach it. */
    private fun markAll(state: FilesUiState): Set<String> {
        val paths = state.entries.map(FileEntry::path)
        if (paths.isEmpty()) return state.marked
        return if (state.marked.containsAll(paths)) {
            state.marked - paths.toSet()
        } else {
            LinkedHashSet(state.marked).apply { addAll(paths) }
        }
    }

    private fun state(marked: Set<String> = emptySet()) =
        FilesUiState(path = "/storage", entries = here, marked = marked)

    @Test
    fun `it ticks everything in the folder`() {
        assertThat(markAll(state()))
            .containsExactly("/storage/a.txt", "/storage/b.txt")
    }

    @Test
    fun `a second press unticks the folder again`() {
        val all = setOf("/storage/a.txt", "/storage/b.txt")

        assertThat(markAll(state(all))).isEmpty()
    }

    /** The regression: a mark in another folder must survive both presses. */
    @Test
    fun `marks made elsewhere are left alone`() {
        val withElsewhere = state(setOf("/elsewhere/rom.sfc"))

        val ticked = markAll(withElsewhere)
        assertThat(ticked).contains("/elsewhere/rom.sfc")
        assertThat(ticked).hasSize(3)

        val unticked = markAll(state(ticked))
        assertThat(unticked).containsExactly("/elsewhere/rom.sfc")
    }

    @Test
    fun `an empty folder changes nothing`() {
        val empty = FilesUiState(
            path = "/storage",
            entries = emptyList(),
            marked = setOf("/elsewhere/rom.sfc"),
        )

        assertThat(markAll(empty)).containsExactly("/elsewhere/rom.sfc")
    }
}
