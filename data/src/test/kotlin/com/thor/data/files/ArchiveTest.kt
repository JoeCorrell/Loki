package com.thor.data.files

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packing and unpacking, against a real filesystem.
 *
 * Worth real files rather than mocks: everything interesting here is what lands
 * on disk, and the case that matters most — an archive whose entry names try to
 * write outside the folder being extracted into — cannot be expressed against a
 * mocked file at all.
 */
class ArchiveTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // A real local source; the SMB one claims only `smb://` paths and so is never
    // consulted by anything here.
    private val repository = FileRepository(
        local = LocalFileSource(),
        remote = SmbFileSource { emptyList() },
        // Never called: nothing here scans a network.
        discovery = mockk(relaxed = true),
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `a folder packs and unpacks back to what it was`() = runTest {
        val source = temporaryFolder.newFolder("Saves")
        File(source, "slot1.srm").writeText("first")
        File(source, "nested").mkdirs()
        File(source, "nested/slot2.srm").writeText("second")

        val packed = repository.compress(
            paths = listOf(source.absolutePath),
            destination = temporaryFolder.root.absolutePath,
            archiveName = "Saves",
        )
        assertThat(packed).isEqualTo(FileResult.Done)

        val archive = File(temporaryFolder.root, "Saves.zip")
        assertThat(archive.isFile).isTrue()

        source.deleteRecursively()
        assertThat(repository.extract(archive.absolutePath)).isEqualTo(FileResult.Done)

        val restored = File(temporaryFolder.root, "Saves")
        assertThat(File(restored, "Saves/slot1.srm").readText()).isEqualTo("first")
        assertThat(File(restored, "Saves/nested/slot2.srm").readText()).isEqualTo("second")
    }

    /**
     * Zip Slip: an entry named `../…` must not write outside the destination.
     *
     * A real attack rather than a theoretical one — any archive can carry it, and
     * a file manager that extracts wherever the entry name says will happily
     * overwrite whatever it points at. The whole extraction is refused rather than
     * the bad entry skipped: an archive containing one of these is not an archive
     * to trust the remainder of.
     */
    @Test
    fun `an entry escaping the destination is refused`() = runTest {
        val archive = File(temporaryFolder.root, "evil.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../escaped.txt"))
            zip.write("owned".toByteArray())
            zip.closeEntry()
        }

        val result = repository.extract(archive.absolutePath)

        assertThat(result).isInstanceOf(FileResult.Failed::class.java)
        assertThat(File(temporaryFolder.root.parentFile, "escaped.txt").exists()).isFalse()
    }

    /** And nothing half-extracted is left lying about afterwards. */
    @Test
    fun `a refused archive leaves no folder behind`() = runTest {
        val archive = File(temporaryFolder.root, "evil.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("innocent.txt"))
            zip.write("fine".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("../escaped.txt"))
            zip.write("owned".toByteArray())
            zip.closeEntry()
        }

        repository.extract(archive.absolutePath)

        assertThat(File(temporaryFolder.root, "evil").exists()).isFalse()
    }

    @Test
    fun `only zip archives are offered to extract`() = runTest {
        val notAZip = File(temporaryFolder.root, "disc.7z")
        notAZip.writeText("not really an archive")

        val result = repository.extract(notAZip.absolutePath)

        assertThat(result).isInstanceOf(FileResult.Invalid::class.java)
    }

    @Test
    fun `packing over an existing archive is refused rather than replacing it`() = runTest {
        File(temporaryFolder.root, "Saves.zip").writeText("something already here")
        val source = temporaryFolder.newFile("slot1.srm")

        val result = repository.compress(
            paths = listOf(source.absolutePath),
            destination = temporaryFolder.root.absolutePath,
            archiveName = "Saves",
        )

        assertThat(result).isInstanceOf(FileResult.Invalid::class.java)
    }

    /** Extracting the same archive twice does not merge into the first folder. */
    @Test
    fun `a second extraction gets a folder of its own`() = runTest {
        val archive = File(temporaryFolder.root, "Pack.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("file.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }

        repository.extract(archive.absolutePath)
        repository.extract(archive.absolutePath)

        assertThat(File(temporaryFolder.root, "Pack").isDirectory).isTrue()
        assertThat(File(temporaryFolder.root, "Pack (2)").isDirectory).isTrue()
    }

    /** A half-written archive must not be left looking like a finished one. */
    @Test
    fun `no partial file survives a successful pack`() = runTest {
        val source = temporaryFolder.newFile("slot1.srm")
        source.writeText("data")

        repository.compress(
            paths = listOf(source.absolutePath),
            destination = temporaryFolder.root.absolutePath,
            archiveName = "Saves",
        )

        assertThat(temporaryFolder.root.list()?.none { it.endsWith(".part") }).isTrue()
    }
}
