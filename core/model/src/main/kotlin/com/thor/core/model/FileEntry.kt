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
    /**
     * How many things are directly inside, for a directory.
     *
     * Null when it was not counted, which is not the same as zero and is why this
     * is nullable rather than defaulting to 0 — a row that says "0 items" about a
     * folder nobody looked inside is a claim, and a wrong one. Counting costs a
     * `readdir` per directory, so the repository only does it where the parent is
     * small enough for that to be free; see `FileRepository.list`.
     */
    val childCount: Int? = null,
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
    if (isRemotePath(path)) return remoteBreadcrumbs(path)

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

/**
 * Crumbs for a share, where the first two segments are not folders.
 *
 * `smb://tower/games/snes` is a *server*, a *share* and then a directory, and
 * the header has to be able to say so — walking up from `snes` reaches the list
 * of shares on `tower`, which is a real place with a real listing rather than a
 * prefix of a string. Splitting the generic path builder's way would produce a
 * crumb for `smb:` and one for an empty segment, both of them dead.
 *
 * Every crumb keeps its trailing slash, because jcifs only treats a path as a
 * directory when it has one.
 */
private fun remoteBreadcrumbs(path: String): List<Breadcrumb> {
    val body = path.removePrefix(SMB_SCHEME).trim('/')
    if (body.isEmpty()) return emptyList()

    val segments = body.split('/').filter(String::isNotEmpty)
    val crumbs = mutableListOf<Breadcrumb>()
    val builder = StringBuilder(SMB_SCHEME)

    segments.forEach { segment ->
        builder.append(segment).append('/')
        crumbs += Breadcrumb(label = segment, path = builder.toString())
    }
    return crumbs
}

data class Breadcrumb(val label: String, val path: String)

/**
 * The folder [path] lives in, spelled the way the explorer spells folders.
 *
 * Its own function because the explorer used `java.io.File` for this, and on
 * `smb://tower/games/rom.iso` that produces `smb:/tower/games` — one slash short,
 * matching nothing. The mark set is keyed on exactly this comparison, so getting
 * it wrong means a file ticked on a share is silently counted as being somewhere
 * else, and every action that reports "3 elsewhere" reports it about files that
 * are in plain view.
 *
 * A directory's parent keeps the trailing slash on a remote path, because that is
 * how a remote directory is written everywhere else — see `SmbFileSource`.
 */
fun parentPathOf(path: String): String {
    if (!isRemotePath(path)) return path.substringBeforeLast('/', "")

    val body = path.removePrefix(SMB_SCHEME).trim('/')
    val segments = body.split('/').filter(String::isNotEmpty)
    if (segments.size <= 1) return ""
    return SMB_SCHEME + segments.dropLast(1).joinToString("/") + "/"
}

/** The last segment of [path], with no trailing separator. */
fun fileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** [path]'s neighbour, named [newName]. Used to follow the cursor after a rename. */
fun siblingPath(path: String, newName: String): String {
    val parent = parentPathOf(path)
    return when {
        parent.isEmpty() -> newName
        parent.endsWith('/') -> "$parent$newName"
        else -> "$parent/$newName"
    }
}

/** [child] inside [parent], joined the way that parent's storage spells a path. */
fun childPathOf(parent: String, child: String): String =
    if (parent.endsWith('/')) "$parent$child" else "$parent/$child"

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
