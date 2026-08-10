package com.thor.data.files

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Copying and moving, against a real filesystem.
 *
 * These exist because of a bug that destroyed files rather than merely failing to
 * move them, and nothing about the code looked wrong: pasting into the folder
 * something already lives in resolved the destination to the source itself, so the
 * copy opened a read stream and a write stream on one path. Opening for write
 * truncates, the read then found nothing, nought bytes were reported as a success
 * — and a move deleted what was left of it.
 */
class TransferTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val repository = FileRepository(
        appContext = mockk(relaxed = true),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun folder(name: String) = temporaryFolder.newFolder(name)

    private fun file(parent: File, name: String, text: String) =
        File(parent, name).apply { writeText(text) }

    // ---- Pasting where it already is ---------------------------------------

    /** The regression, at its simplest: the file must survive. */
    @Test
    fun `moving a file into the folder it is already in leaves it alone`() = runTest {
        val here = folder("here")
        val rom = file(here, "game.sfc", "cartridge")

        val result = repository.transfer(
            paths = listOf(rom.absolutePath),
            destination = here.absolutePath,
            move = true,
        )

        assertThat(result).isEqualTo(FileResult.Done)
        assertThat(rom.exists()).isTrue()
        assertThat(rom.readText()).isEqualTo("cartridge")
    }

    /** And on a folder, where it used to empty every child before deleting the lot. */
    @Test
    fun `moving a folder into its own parent leaves it intact`() = runTest {
        val parent = folder("parent")
        val saves = File(parent, "Saves").apply { mkdirs() }
        file(saves, "slot1.srm", "first")
        file(saves, "slot2.srm", "second")

        val result = repository.transfer(
            paths = listOf(saves.absolutePath),
            destination = parent.absolutePath,
            move = true,
        )

        assertThat(result).isEqualTo(FileResult.Done)
        assertThat(File(saves, "slot1.srm").readText()).isEqualTo("first")
        assertThat(File(saves, "slot2.srm").readText()).isEqualTo("second")
    }

    /** Copying in place is a real request, and gets a second copy rather than nothing. */
    @Test
    fun `copying a file into its own folder makes a second one`() = runTest {
        val here = folder("here")
        val rom = file(here, "game.sfc", "cartridge")

        val result = repository.transfer(
            paths = listOf(rom.absolutePath),
            destination = here.absolutePath,
            move = false,
        )

        assertThat(result).isEqualTo(FileResult.Done)
        assertThat(rom.readText()).isEqualTo("cartridge")
        assertThat(File(here, "game.sfc (2)").readText()).isEqualTo("cartridge")
    }

    // ---- Not writing over things -------------------------------------------

    /**
     * A paste never replaces a file that is already there.
     *
     * The one destructive act in this class with no confirmation in front of it,
     * if it were allowed. `Name (2)` costs a rename at worst.
     */
    @Test
    fun `an existing file of the same name is not overwritten`() = runTest {
        val from = folder("from")
        val to = folder("to")
        file(from, "game.sfc", "new")
        val existing = file(to, "game.sfc", "old")

        repository.transfer(
            paths = listOf(File(from, "game.sfc").absolutePath),
            destination = to.absolutePath,
            move = false,
        )

        assertThat(existing.readText()).isEqualTo("old")
        assertThat(File(to, "game.sfc (2)").readText()).isEqualTo("new")
    }

    // ---- Ordinary moves ----------------------------------------------------

    @Test
    fun `a move takes the file with it and leaves nothing behind`() = runTest {
        val from = folder("from")
        val to = folder("to")
        val rom = file(from, "game.sfc", "cartridge")

        val result = repository.transfer(
            paths = listOf(rom.absolutePath),
            destination = to.absolutePath,
            move = true,
        )

        assertThat(result).isEqualTo(FileResult.Done)
        assertThat(rom.exists()).isFalse()
        assertThat(File(to, "game.sfc").readText()).isEqualTo("cartridge")
    }

    @Test
    fun `a copy leaves the original where it was`() = runTest {
        val from = folder("from")
        val to = folder("to")
        val rom = file(from, "game.sfc", "cartridge")

        repository.transfer(
            paths = listOf(rom.absolutePath),
            destination = to.absolutePath,
            move = false,
        )

        assertThat(rom.readText()).isEqualTo("cartridge")
        assertThat(File(to, "game.sfc").readText()).isEqualTo("cartridge")
    }

    @Test
    fun `a folder moves with everything inside it`() = runTest {
        val from = folder("from")
        val to = folder("to")
        val saves = File(from, "Saves").apply { mkdirs() }
        File(saves, "nested").mkdirs()
        file(saves, "slot1.srm", "first")
        file(File(saves, "nested"), "slot2.srm", "second")

        val result = repository.transfer(
            paths = listOf(saves.absolutePath),
            destination = to.absolutePath,
            move = true,
        )

        assertThat(result).isEqualTo(FileResult.Done)
        assertThat(saves.exists()).isFalse()
        assertThat(File(to, "Saves/slot1.srm").readText()).isEqualTo("first")
        assertThat(File(to, "Saves/nested/slot2.srm").readText()).isEqualTo("second")
    }

    /** The classic way to fill a device, and still refused. */
    @Test
    fun `a folder cannot be pasted inside itself`() = runTest {
        val saves = folder("Saves")
        val inside = File(saves, "inside").apply { mkdirs() }
        file(saves, "slot1.srm", "first")

        val result = repository.transfer(
            paths = listOf(saves.absolutePath),
            destination = inside.absolutePath,
            move = false,
        )

        assertThat(result).isInstanceOf(FileResult.Invalid::class.java)
        assertThat(File(saves, "slot1.srm").readText()).isEqualTo("first")
    }

    /** Progress must reach its total, or the bar stops short of the end. */
    @Test
    fun `progress ends at the total it was given`() = runTest {
        val from = folder("from")
        val to = folder("to")
        file(from, "game.sfc", "a".repeat(4096))

        var lastDone = 0L
        var lastTotal = 0L
        repository.transfer(
            paths = listOf(File(from, "game.sfc").absolutePath),
            destination = to.absolutePath,
            move = false,
            onProgress = { done, total -> lastDone = done; lastTotal = total },
        )

        assertThat(lastTotal).isEqualTo(4096L)
        assertThat(lastDone).isEqualTo(lastTotal)
    }
}
