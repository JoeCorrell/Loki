package com.thor.data.files

import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.thor.core.model.FileEntry
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The device's own storage.
 *
 * Real paths through `java.io.File` rather than SAF, which is what
 * `MANAGE_EXTERNAL_STORAGE` buys and the reason the manifest asks for it: a file
 * manager has to copy between two arbitrary folders and rename in place, and SAF
 * only ever sees what has been granted a folder at a time.
 *
 * No dispatcher here. Every call arrives on IO already — the repository moves
 * once, at its own boundary, rather than each of these hopping again for the
 * dozens of calls a single copy makes.
 */
@Singleton
class LocalFileSource @Inject constructor() : FileSource {

    /**
     * Whether the launcher may actually read the device.
     *
     * Declaring [android.Manifest.permission.MANAGE_EXTERNAL_STORAGE] is not being
     * granted it — the user turns it on in Android's own "All files access" screen.
     * Asked rather than assumed so the explorer can say *why* it is empty, which is
     * the difference between a device with nothing on it and a permission nobody
     * mentioned.
     */
    val hasStorageAccess: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Below API 30 the legacy read permission is the whole story, and it is
            // granted at install time on those versions.
            true
        }

    /**
     * Everything that is not somebody else's.
     *
     * The catch-all, and so it is asked last: a path with no recognised scheme is
     * a path on this device, which is what every absolute path in the launcher has
     * always been.
     */
    override fun handles(path: String): Boolean = true

    /** Where the explorer opens: the user's own storage, not the filesystem root. */
    fun defaultDirectory(): String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: ROOT

    /**
     * The places worth offering as a starting point.
     *
     * Only ones that exist and can be read — a shortcut to a folder that is not
     * there is a dead end the user has to discover by pressing it.
     */
    override suspend fun shortcuts(): List<FileShortcut> {
        val home = Environment.getExternalStorageDirectory()

        return buildList {
            home?.let { add(FileShortcut("Internal storage", it.absolutePath)) }
            listOf(
                Environment.DIRECTORY_DOWNLOADS to "Downloads",
                Environment.DIRECTORY_PICTURES to "Pictures",
                Environment.DIRECTORY_MOVIES to "Movies",
                Environment.DIRECTORY_MUSIC to "Music",
                Environment.DIRECTORY_DCIM to "Camera",
            ).forEach { (type, label) ->
                Environment.getExternalStoragePublicDirectory(type)
                    ?.takeIf { it.isDirectory }
                    ?.let { add(FileShortcut(label, it.absolutePath)) }
            }

            // Removable cards, which is where a handheld's library usually lives.
            // Found by walking /storage rather than by a media API, because the one
            // thing wanted here is a path.
            File(ROOT).listFiles()
                ?.filter { it.isDirectory && it.canRead() && it.name !in NON_VOLUMES }
                ?.filterNot { home != null && it.absolutePath == home.absolutePath }
                ?.forEach { add(FileShortcut(it.name, it.absolutePath)) }
        }.distinctBy(FileShortcut::path)
    }

    /**
     * One directory's contents.
     *
     * Unsorted — ordering is [com.thor.core.model.sortFiles]'s job, and it is a
     * pure function the caller can re-run when the user changes the sort without
     * touching the disk again.
     *
     * Cancellation is checked as it goes: a listing of a very large directory is
     * the one place here that can outlive the screen that asked for it, and
     * finishing it after the user has navigated away is work nobody will see.
     */
    override suspend fun list(path: String): FileListing {
        if (!hasStorageAccess) return FileListing.NoAccess

        val directory = File(path)
        if (!directory.isDirectory) return FileListing.Missing
        val children = directory.listFiles() ?: return FileListing.Unreadable

        /*
         * Child counts, where they are cheap.
         *
         * A folder row is far more useful reading "12 items" than reading nothing,
         * and the count is one `readdir` per subdirectory. That is free on a folder
         * of twenty and is emphatically not free on a ROM directory holding four
         * thousand — so it is done only where the parent is small enough that the
         * extra pass cannot be felt, and left null everywhere else. Null renders as
         * no badge at all rather than as "0 items", which would be a wrong answer
         * rather than an absent one.
         */
        val countChildren = children.size <= CHILD_COUNT_LIMIT

        val entries = buildList(children.size) {
            children.forEach { child ->
                coroutineContext.ensureActive()
                add(child.toEntry(countChildren))
            }
        }

        return FileListing.Loaded(path = directory.absolutePath, entries = entries)
    }

    override fun parentOf(path: String): String? =
        File(path).parentFile?.absolutePath?.takeIf { path != ROOT && it.isNotEmpty() }

    override fun childPath(parent: String, name: String): String =
        File(parent, name).absolutePath

    override fun nameOf(path: String): String = File(path).name

    override suspend fun entryAt(path: String): FileEntry? =
        File(path).takeIf { it.exists() }?.toEntry()

    /** Free and total bytes on the volume holding [path], for the details pane. */
    override suspend fun volumeSpace(path: String): VolumeSpace? = runCatching {
        val stat = StatFs(path)
        VolumeSpace(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
    }.getOrNull()

    // ---- Changing things ---------------------------------------------------

    override suspend fun createDirectory(parent: String, name: String): FileResult {
        val target = File(parent, name.sanitized())
        return when {
            name.sanitized().isEmpty() -> FileResult.Invalid("That name cannot be used")
            target.exists() -> FileResult.Invalid("${target.name} already exists")
            target.mkdirs() -> FileResult.Done
            else -> FileResult.Failed("Could not create ${target.name}")
        }
    }

    /**
     * Renames in place.
     *
     * Within the same directory only, which is what "rename" means to a user;
     * moving is the repository's transfer and has to handle crossing volumes.
     */
    override suspend fun rename(path: String, newName: String): FileResult {
        val source = File(path)
        val target = File(source.parentFile, newName.sanitized())

        return when {
            newName.sanitized().isEmpty() -> FileResult.Invalid("That name cannot be used")
            !source.exists() -> FileResult.Failed("${source.name} is no longer there")
            target.exists() -> FileResult.Invalid("${target.name} already exists")
            source.renameTo(target) -> FileResult.Done
            else -> FileResult.Failed("Could not rename ${source.name}")
        }
    }

    /**
     * Deletes, recursively for a directory.
     *
     * No trash. Android gives an app no shared wastebasket to move a file into, and
     * a private one would silently consume the user's storage while telling them
     * they had freed it. So this is permanent, and the surface asking for it is
     * responsible for saying so first.
     */
    override suspend fun delete(paths: List<String>): FileResult {
        var failed = 0
        paths.forEach { path ->
            coroutineContext.ensureActive()
            if (!deleteTree(path)) failed++
        }

        return when (failed) {
            0 -> FileResult.Done
            paths.size -> FileResult.Failed("Nothing could be deleted")
            else -> FileResult.Failed("$failed of ${paths.size} could not be deleted")
        }
    }

    // ---- Primitives --------------------------------------------------------

    override suspend fun exists(path: String): Boolean = File(path).exists()

    override suspend fun isDirectory(path: String): Boolean = File(path).isDirectory

    override suspend fun children(path: String): List<String>? =
        File(path).listFiles()?.map { it.absolutePath }

    override suspend fun sizeOnDisk(path: String): Long = File(path).let { file ->
        if (file.isDirectory) {
            file.walkTopDown().filter(File::isFile).sumOf(File::length)
        } else {
            file.length()
        }
    }

    override suspend fun openRead(path: String): InputStream = File(path).inputStream()

    override suspend fun openWrite(path: String): OutputStream = File(path).outputStream()

    override suspend fun mkdirs(path: String): Boolean =
        File(path).let { it.isDirectory || it.mkdirs() }

    override suspend fun deleteTree(path: String): Boolean = runCatching {
        val file = File(path)
        if (file.isDirectory) file.deleteRecursively() else file.delete()
    }.getOrDefault(false)

    /**
     * A move within one volume is a rename, and a rename is instantaneous.
     *
     * `renameTo` fails across volumes — internal storage to a card — and returns
     * false rather than throwing, which is exactly the contract this method wants.
     */
    override suspend fun moveWithin(from: String, to: String): Boolean = runCatching {
        val source = File(from).toPath()
        val target = File(to).toPath()
        if (!Files.exists(source) || Files.exists(target)) return@runCatching false

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Still do not pass REPLACE_EXISTING: a destination created between
            // the check and this call must win rather than be overwritten.
            Files.move(source, target)
        }
        true
    }.getOrDefault(false)

    override suspend fun identityOf(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    // ---- Plumbing ----------------------------------------------------------

    private fun File.toEntry(countChildren: Boolean = false) = FileEntry(
        path = absolutePath,
        name = name,
        isDirectory = isDirectory,
        // A directory's own byte count says nothing a user wants; the details pane
        // measures a folder on request rather than every row paying for a walk.
        sizeBytes = if (isDirectory) -1L else length(),
        modifiedEpochMs = lastModified(),
        isHidden = name.startsWith('.'),
        canWrite = canWrite(),
        childCount = if (countChildren && isDirectory) list()?.size else null,
    )

    private companion object {
        const val ROOT = "/storage"

        /** Above this, counting each subfolder's contents stops being free. */
        const val CHILD_COUNT_LIMIT = 250

        /** Entries under /storage that are not volumes the user cares about. */
        val NON_VOLUMES = setOf("self", "emulated", "enc_emulated")
    }
}

/** Strips what a filesystem cannot hold, so a bad name fails here and not deeper. */
internal fun String.sanitized(): String {
    val cleaned = trim()
        .filterNot { it in ILLEGAL_NAME_CHARS || it.isISOControl() }
        .trimEnd(' ', '.')
        .take(MAX_NAME)

    return if (cleaned.substringBefore('.').uppercase() in RESERVED_NAMES) "" else cleaned
}

internal const val MAX_NAME = 255

/** Reserved on the filesystems Android mounts, FAT included, and on SMB. */
internal val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

/** Windows/SMB device names, also rejected on local storage for portable files. */
private val RESERVED_NAMES = buildSet {
    addAll(listOf("CON", "PRN", "AUX", "NUL", "CLOCK$"))
    (1..9).forEach { number ->
        add("COM$number")
        add("LPT$number")
    }
}
