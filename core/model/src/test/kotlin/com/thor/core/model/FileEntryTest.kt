package com.thor.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How a directory is ordered and described.
 *
 * The explorer walks these lists with a d-pad, so ordering is not cosmetic: an
 * unstable sort moves the row under the cursor between visits, and a listing that
 * mixes folders among files makes "go up one level" a hunt.
 */
class FileEntryTest {

    private fun file(
        name: String,
        size: Long = 0L,
        modified: Long = 0L,
        directory: Boolean = false,
        hidden: Boolean = name.startsWith("."),
    ) = FileEntry(
        path = "/storage/$name",
        name = name,
        isDirectory = directory,
        sizeBytes = size,
        modifiedEpochMs = modified,
        isHidden = hidden,
        canWrite = true,
    )

    // ---- Ordering ----------------------------------------------------------

    @Test
    fun `folders come first whatever the sort`() {
        val entries = listOf(file("b.txt"), file("a-folder", directory = true))

        FileSort.entries.forEach { sort ->
            val sorted = sortFiles(entries, sort)
            assertThat(sorted.first().isDirectory).isTrue()
        }
    }

    /**
     * Reversing a sort must not reverse "folders first".
     *
     * Reversing the whole comparator would put every folder at the bottom, which
     * is not what anybody means by sorting a file list backwards.
     */
    @Test
    fun `folders stay first when the order is reversed`() {
        val entries = listOf(file("z.txt"), file("a-folder", directory = true))

        val sorted = sortFiles(entries, FileSort.NAME, descending = true)

        assertThat(sorted.first().isDirectory).isTrue()
    }

    @Test
    fun `equal values fall back to name so the list is stable`() {
        val entries = listOf(file("b.txt", size = 10), file("a.txt", size = 10))

        val sorted = sortFiles(entries, FileSort.SIZE)

        assertThat(sorted.map { it.name }).containsExactly("a.txt", "b.txt").inOrder()
    }

    @Test
    fun `name ordering ignores case`() {
        val entries = listOf(file("Zebra"), file("apple"))

        val sorted = sortFiles(entries, FileSort.NAME)

        assertThat(sorted.map { it.name }).containsExactly("apple", "Zebra").inOrder()
    }

    @Test
    fun `hidden files are left out unless asked for`() {
        val entries = listOf(file(".config"), file("visible.txt"))

        assertThat(sortFiles(entries, FileSort.NAME).map { it.name })
            .containsExactly("visible.txt")
        assertThat(sortFiles(entries, FileSort.NAME, showHidden = true)).hasSize(2)
    }

    // ---- What a file is ----------------------------------------------------

    @Test
    fun `kind is read from the extension`() {
        assertThat(file("art.png").kind).isEqualTo(FileKind.IMAGE)
        assertThat(file("clip.mkv").kind).isEqualTo(FileKind.VIDEO)
        assertThat(file("song.flac").kind).isEqualTo(FileKind.AUDIO)
        assertThat(file("app.apk").kind).isEqualTo(FileKind.APP)
        assertThat(file("notes.md").kind).isEqualTo(FileKind.DOCUMENT)
        assertThat(file("mystery.qqq").kind).isEqualTo(FileKind.OTHER)
    }

    /** ROM extensions come from the scanner's own list, not a second copy of it. */
    @Test
    fun `a rom is recognised as a game`() {
        val romExtension = BuiltInPlatforms.ALL
            .flatMap { it.romExtensions }
            .first { it !in setOf("zip", "7z", "rar") }

        assertThat(file("game.$romExtension").kind).isEqualTo(FileKind.GAME)
    }

    /**
     * An archive stays an archive even where a console also accepts one.
     *
     * What the user can do with a zip is extract it, whichever emulator would also
     * have opened it.
     */
    @Test
    fun `an archive is not reclassified as a game`() {
        assertThat(file("bundle.zip").kind).isEqualTo(FileKind.ARCHIVE)
    }

    /**
     * A dotted directory name is still a directory.
     *
     * `com.thor.launcher` is a folder, and reading `launcher` off it as a type is
     * how a file manager offers to open a directory with a video player.
     */
    @Test
    fun `a directory has no extension`() {
        val folder = file("com.thor.launcher", directory = true)

        assertThat(folder.extension).isEmpty()
        assertThat(folder.kind).isEqualTo(FileKind.FOLDER)
    }

    // ---- Crumbs ------------------------------------------------------------

    @Test
    fun `breadcrumbs carry the path to each level`() {
        val crumbs = breadcrumbs("/storage/emulated/0/Roms")

        assertThat(crumbs.map { it.label })
            .containsExactly("Storage", "storage", "emulated", "0", "Roms").inOrder()
        assertThat(crumbs.last().path).isEqualTo("/storage/emulated/0/Roms")
        assertThat(crumbs[2].path).isEqualTo("/storage/emulated")
    }

    @Test
    fun `the root is a single crumb`() {
        assertThat(breadcrumbs("/").map { it.label }).containsExactly("Storage")
    }

    // ---- Sizes -------------------------------------------------------------

    @Test
    fun `sizes read in binary units`() {
        assertThat(formatFileSize(512)).isEqualTo("512 B")
        assertThat(formatFileSize(1024)).isEqualTo("1.0 KB")
        assertThat(formatFileSize(1536)).isEqualTo("1.5 KB")
        assertThat(formatFileSize(5L * 1024 * 1024 * 1024)).isEqualTo("5.0 GB")
    }

    /** Past a hundred the decimal is noise: "847 MB", not "847.3 MB". */
    @Test
    fun `the decimal is dropped once it stops meaning anything`() {
        assertThat(formatFileSize(847L * 1024 * 1024)).isEqualTo("847 MB")
    }

    @Test
    fun `an unknown size is blank rather than a lie`() {
        assertThat(formatFileSize(-1)).isEmpty()
    }
}
