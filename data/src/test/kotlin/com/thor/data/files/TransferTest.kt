package com.thor.data.files

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream

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

    /**
     * A real local source and an SMB source with no servers behind it.
     *
     * Not a mock of either. The whole point of these tests is that the copy engine
     * behaves against a real filesystem, and the SMB source is inert here for a
     * reason rather than by stubbing: it claims only `smb://` paths, so with every
     * path in these tests being a temporary folder it is never consulted.
     */
    private val repository = FileRepository(
        local = LocalFileSource(),
        remote = SmbFileSource { emptyList() },
        // Never called: nothing here scans a network.
        discovery = mockk(relaxed = true),
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

    @Test
    fun `a failed copy leaves the source intact and no partial destination`() = runTest {
        val from = folder("from")
        val to = folder("to")
        val original = ByteArray(2 * 1024 * 1024) { index -> (index % 251).toByte() }
        val rom = File(from, "disc.chd").apply { writeBytes(original) }

        val result = repository.transfer(
            paths = listOf(rom.absolutePath),
            destination = to.absolutePath,
            move = false,
            onProgress = { copied, _ ->
                if (copied > 0L) error("simulated destination failure")
            },
        )

        assertThat(result).isInstanceOf(FileResult.Failed::class.java)
        assertThat(rom.readBytes()).isEqualTo(original)
        assertThat(File(to, "disc.chd").exists()).isFalse()
        assertThat(to.list().orEmpty().none { it.startsWith(".loki-part-") }).isTrue()
    }

    @Test
    fun `many name collisions never fall back to overwriting the first file`() = runTest {
        val from = folder("from")
        val to = folder("to")
        file(from, "game.sfc", "new")
        file(to, "game.sfc", "original")
        (2..100).forEach { number -> file(to, "game.sfc ($number)", "keep-$number") }

        val result = repository.transfer(
            paths = listOf(File(from, "game.sfc").absolutePath),
            destination = to.absolutePath,
            move = false,
        )

        assertThat(result).isEqualTo(FileResult.Done)
        assertThat(File(to, "game.sfc").readText()).isEqualTo("original")
        assertThat(File(to, "game.sfc (100)").readText()).isEqualTo("keep-100")
        assertThat(File(to, "game.sfc (101)").readText()).isEqualTo("new")
    }

    @Test
    fun `a move never deletes its source when destination verification fails`() = runTest {
        val to = folder("to")
        val sourcePath = "smb://nas/share/game.sfc"
        val remote = mockk<SmbFileSource>()
        every { remote.handles(any()) } answers {
            firstArg<String>().startsWith("smb://")
        }
        every { remote.nameOf(sourcePath) } returns "game.sfc"
        coEvery { remote.exists(sourcePath) } returns true
        coEvery { remote.sizeOnDisk(sourcePath) } returns 100L
        coEvery { remote.isDirectory(sourcePath) } returns false
        coEvery { remote.openRead(sourcePath) } returns
            ByteArrayInputStream("too short".toByteArray())
        coEvery { remote.deleteTree(sourcePath) } returns true

        val crossSource = FileRepository(
            local = LocalFileSource(),
            remote = remote,
            discovery = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = crossSource.transfer(
            paths = listOf(sourcePath),
            destination = to.absolutePath,
            move = true,
        )

        assertThat(result).isInstanceOf(FileResult.Failed::class.java)
        assertThat(File(to, "game.sfc").exists()).isFalse()
        assertThat(to.list().orEmpty().none { it.startsWith(".loki-part-") }).isTrue()
        coVerify(exactly = 0) { remote.deleteTree(sourcePath) }
    }

    @Test
    fun `a move keeps both copies and reports when source removal fails`() = runTest {
        val to = folder("to")
        val sourcePath = "smb://nas/share/game.sfc"
        val bytes = "cartridge".toByteArray()
        val remote = mockk<SmbFileSource>()
        every { remote.handles(any()) } answers {
            firstArg<String>().startsWith("smb://")
        }
        every { remote.nameOf(sourcePath) } returns "game.sfc"
        coEvery { remote.exists(sourcePath) } returns true
        coEvery { remote.sizeOnDisk(sourcePath) } returns bytes.size.toLong()
        coEvery { remote.isDirectory(sourcePath) } returns false
        coEvery { remote.openRead(sourcePath) } returns ByteArrayInputStream(bytes)
        coEvery { remote.deleteTree(sourcePath) } returns false

        val crossSource = FileRepository(
            local = LocalFileSource(),
            remote = remote,
            discovery = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = crossSource.transfer(
            paths = listOf(sourcePath),
            destination = to.absolutePath,
            move = true,
        )

        assertThat(result).isInstanceOf(FileResult.Failed::class.java)
        assertThat((result as FileResult.Failed).reason).contains("both copies were kept")
        assertThat(File(to, "game.sfc").readBytes()).isEqualTo(bytes)
        coVerify(exactly = 1) { remote.deleteTree(sourcePath) }
    }

    /**
     * Size equality is not integrity: a device or server can return the requested
     * byte count with damaged content. The staged tree is read back and compared
     * to the digest produced while the source was copied before a move may delete
     * anything.
     */
    @Test
    fun `same size corruption is refused and a move keeps its source`() = runTest {
        val to = folder("to")
        val sourcePath = "smb://nas/share/game.sfc"
        val original = "cartridge".toByteArray()
        val corrupted = "Xartridge".toByteArray()
        val remote = mockk<SmbFileSource>()
        every { remote.handles(any()) } answers {
            firstArg<String>().startsWith("smb://")
        }
        every { remote.nameOf(sourcePath) } returns "game.sfc"
        coEvery { remote.exists(sourcePath) } returns true
        coEvery { remote.sizeOnDisk(sourcePath) } returns original.size.toLong()
        coEvery { remote.isDirectory(sourcePath) } returns false
        coEvery { remote.openRead(sourcePath) } returns ByteArrayInputStream(original)
        coEvery { remote.deleteTree(sourcePath) } returns true

        val crossSource = FileRepository(
            local = LocalFileSource(),
            remote = remote,
            discovery = mockk(relaxed = true),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = crossSource.transfer(
            paths = listOf(sourcePath),
            destination = to.absolutePath,
            move = true,
            onProgress = { copied, total ->
                if (copied == total) {
                    to.listFiles()
                        .orEmpty()
                        .single { it.name.startsWith(".loki-part-") }
                        .writeBytes(corrupted)
                }
            },
        )

        assertThat(result).isInstanceOf(FileResult.Failed::class.java)
        assertThat(File(to, "game.sfc").exists()).isFalse()
        assertThat(to.list().orEmpty().none { it.startsWith(".loki-part-") }).isTrue()
        coVerify(exactly = 0) { remote.deleteTree(sourcePath) }
    }
}
