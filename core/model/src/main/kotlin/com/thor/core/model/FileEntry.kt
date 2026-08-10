package com.thor.core.model

/**
 * One row in the file explorer.
 *
 * A snapshot of what a listing found, not a live handle. The explorer re-lists a
 * directory after anything that changes it rather than mutating rows in place,
 * because the filesystem is shared with every other app on the device and a row
 * held across a copy or a delete is a claim that was true once.
 */
data class FileEntry(
    /** Absolute path. The identity: two entries are the same file if this matches. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
    /** Hidden by the Unix convention, which is the only one a path can express. */
    val isHidden: Boolean,
    /** Whether this process can actually write here; a card may be mounted read-only. */
    val canWrite: Boolean,
) {
    /**
     * The bit after the last dot, lowercased, or empty.
     *
     * Empty for a directory even when its name contains a dot — `com.thor.launcher`
     * is a folder, not a file of type `launcher`, and treating it as one is how a
     * file manager ends up offering to open a directory with a video player.
     */
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val kind: FileKind get() = FileKind.of(this)
}

/**
 * What a file is, as far as the explorer needs to care.
 *
 * By extension rather than by content. Reading a magic number means opening every
 * file in a directory to draw a list of it, which on a folder of ten thousand ROMs
 * is a second of IO to decide which glyph to use. The extension is what the rest of
 * Android dispatches on anyway.
 */
enum class FileKind {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    DOCUMENT,
    APP,

    /** A ROM, by the extensions the launcher already scans for. */
    GAME,

    OTHER,
    ;

    companion object {
        fun of(entry: FileEntry): FileKind {
            if (entry.isDirectory) return FOLDER
            return BY_EXTENSION[entry.extension] ?: OTHER
        }

        /**
         * Extension to kind, written once.
         *
         * Games are deliberately not listed exhaustively here — [BuiltInPlatforms]
         * already knows every ROM extension the launcher scans, and duplicating that
         * list would mean adding a console in two places and finding out later that
         * only one of them took.
         */
        private val BY_EXTENSION: Map<String, FileKind> = buildMap {
            listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")
                .forEach { put(it, IMAGE) }
            listOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "wmv", "flv")
                .forEach { put(it, VIDEO) }
            listOf("mp3", "flac", "wav", "ogg", "m4a", "opus", "aac")
                .forEach { put(it, AUDIO) }
            listOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst")
                .forEach { put(it, ARCHIVE) }
            listOf("txt", "md", "pdf", "json", "xml", "log", "cfg", "ini", "csv")
                .forEach { put(it, DOCUMENT) }
            listOf("apk", "apks", "xapk").forEach { put(it, APP) }

            // Every ROM extension the scanner knows, from the one place that knows
            // them. An archive that is also a ROM extension stays an archive: `zip`
            // is put above and is not overwritten here, because what the user can
            // *do* with it — extract it — is the archive answer.
            BuiltInPlatforms.ALL
                .flatMap { it.romExtensions }
                .forEach { extension -> putIfAbsent(extension.lowercase(), GAME) }
        }
    }
}

/** How a directory is ordered. */
enum class FileSort(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    MODIFIED("Modified"),
    KIND("Kind"),
}

/**
 * A directory's contents, ordered the way the explorer shows them.
 *
 * Folders first in every ordering, and that is not a fifth sort mode — it is a
 * property of all of them. A directory has no size worth comparing and a "largest
 * first" list that opens with folders sorted among files by their inode size is
 * noise. Ordering within each group is what the mode actually chooses.
 *
 * Ties break on name so a listing is stable: two files with the same size or the
 * same timestamp otherwise swap places between visits for no reason the user can
 * see.
 */
fun sortFiles(
    entries: List<FileEntry>,
    sort: FileSort,
    descending: Boolean = false,
    showHidden: Boolean = false,
): List<FileEntry> {
    val visible = if (showHidden) entries else entries.filterNot(FileEntry::isHidden)

    val within: Comparator<FileEntry> = when (sort) {
        FileSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER, FileEntry::name)
        FileSort.SIZE -> compareBy(FileEntry::sizeBytes)
        FileSort.MODIFIED -> compareBy(FileEntry::modifiedEpochMs)
        FileSort.KIND -> compareBy<FileEntry> { it.kind.ordinal }
            .thenBy(String.CASE_INSENSITIVE_ORDER, FileEntry::name)
    }

    val ordered = if (descending) within.reversed() else within

    return visible.sortedWith(
        // Folders first, always, and *before* the direction is applied — reversing
        // a "folders first" comparator would put them last, which is not what
        // reversing a sort means to anybody.
        compareByDescending(FileEntry::isDirectory)
            .then(ordered)
            .thenBy(String.CASE_INSENSITIVE_ORDER, FileEntry::name),
    )
}

/**
 * A path broken into the crumbs a header can draw.
 *
 * Each crumb carries the full path to itself, so pressing one is a navigation
 * rather than a string operation performed again at the call site.
 */
fun breadcrumbs(path: String, rootLabel: String = "Storage"): List<Breadcrumb> {
    val trimmed = path.trim('/')
    if (trimmed.isEmpty()) return listOf(Breadcrumb(rootLabel, "/"))

    val crumbs = mutableListOf(Breadcrumb(rootLabel, "/"))
    val builder = StringBuilder()
    trimmed.split('/').filter(String::isNotEmpty).forEach { segment ->
        builder.append('/').append(segment)
        crumbs += Breadcrumb(segment, builder.toString())
    }
    return crumbs
}

data class Breadcrumb(val label: String, val path: String)

/**
 * A size in the units somebody reading a list wants.
 *
 * Binary units, because that is what a filesystem reports and what every other
 * tool on the device will agree with. One decimal place below a hundred and none
 * above it: "1.4 GB" is worth the character, "847.3 MB" is not.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return ""
    if (bytes < UNIT) return "$bytes B"

    var value = bytes.toDouble()
    var unit = 0
    while (value >= UNIT && unit < UNIT_NAMES.lastIndex) {
        value /= UNIT
        unit++
    }

    return if (value >= 100.0) {
        "%.0f %s".format(value, UNIT_NAMES[unit])
    } else {
        "%.1f %s".format(value, UNIT_NAMES[unit])
    }
}

private const val UNIT = 1024.0
private val UNIT_NAMES = listOf("B", "KB", "MB", "GB", "TB")
