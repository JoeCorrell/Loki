package com.thor.data.files

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.thor.core.common.dispatchers.Dispatcher
import com.thor.core.common.dispatchers.ThorDispatcher
import com.thor.core.common.log.ThorLog
import com.thor.core.model.FileEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * The device's storage, as the explorer sees it.
 *
 * Real paths through `java.io.File` rather than SAF, which is what
 * `MANAGE_EXTERNAL_STORAGE` buys and the reason the manifest asks for it: a file
 * manager has to copy between two arbitrary folders and rename in place, and SAF
 * only ever sees what has been granted a folder at a time.
 *
 * Every method is suspending and moves to IO. Listing a directory of ten thousand
 * ROMs is tens of milliseconds of `stat` calls, which is several dropped frames on
 * the main thread and exactly the directory this launcher is pointed at.
 */
@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @Dispatcher(ThorDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

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

    /** Where the explorer opens: the user's own storage, not the filesystem root. */
    fun defaultDirectory(): String =
        Environment.getExternalStorageDirectory()?.absolutePath ?: ROOT

    /**
     * The places worth offering as a starting point.
     *
     * Only ones that exist and can be read — a shortcut to a folder that is not
     * there is a dead end the user has to discover by pressing it.
     */
    suspend fun shortcuts(): List<FileShortcut> = withContext(ioDispatcher) {
        val home = Environment.getExternalStorageDirectory()

        buildList {
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
    suspend fun list(path: String): FileListing = withContext(ioDispatcher) {
        if (!hasStorageAccess) return@withContext FileListing.NoAccess

        val directory = File(path)
        if (!directory.isDirectory) return@withContext FileListing.Missing
        val children = directory.listFiles() ?: return@withContext FileListing.Unreadable

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

        FileListing.Loaded(path = directory.absolutePath, entries = entries)
    }

    /** The parent of [path], or null at the top of the tree. */
    fun parentOf(path: String): String? =
        File(path).parentFile?.absolutePath?.takeIf { path != ROOT && it.isNotEmpty() }

    suspend fun entryAt(path: String): FileEntry? = withContext(ioDispatcher) {
        File(path).takeIf { it.exists() }?.toEntry()
    }

    /** Free and total bytes on the volume holding [path], for the details pane. */
    suspend fun volumeSpace(path: String): VolumeSpace? = withContext(ioDispatcher) {
        runCatching {
            val stat = StatFs(path)
            VolumeSpace(
                freeBytes = stat.availableBytes,
                totalBytes = stat.totalBytes,
            )
        }.getOrNull()
    }

    // ---- Changing things ---------------------------------------------------

    suspend fun createDirectory(parent: String, name: String): FileResult =
        withContext(ioDispatcher) {
            val target = File(parent, name.sanitized())
            when {
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
     * moving is [move] and has to handle crossing volumes.
     */
    suspend fun rename(path: String, newName: String): FileResult = withContext(ioDispatcher) {
        val source = File(path)
        val target = File(source.parentFile, newName.sanitized())

        when {
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
    suspend fun delete(paths: List<String>): FileResult = withContext(ioDispatcher) {
        var failed = 0
        paths.forEach { path ->
            coroutineContext.ensureActive()
            val file = File(path)
            val gone = runCatching {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }.getOrDefault(false)
            if (!gone) failed++
        }

        when (failed) {
            0 -> FileResult.Done
            paths.size -> FileResult.Failed("Nothing could be deleted")
            else -> FileResult.Failed("$failed of ${paths.size} could not be deleted")
        }
    }

    /**
     * Copies into [destination], reporting progress as it goes.
     *
     * Byte-counted rather than file-counted: a folder of one 8 GB disc image and
     * four hundred saves is one file away from finished for almost the whole
     * operation if you count files, which makes the bar a lie exactly when the user
     * is watching it.
     *
     * @param move deletes each source once its copy has landed. Not `renameTo`,
     *   which fails silently across volumes — the common case here, since the point
     *   is usually moving between internal storage and a card.
     */
    suspend fun transfer(
        paths: List<String>,
        destination: String,
        move: Boolean,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FileResult = withContext(ioDispatcher) {
        val sources = paths.map(::File).filter(File::exists)
        if (sources.isEmpty()) return@withContext FileResult.Failed("Nothing left to copy")

        val target = File(destination)
        if (!target.isDirectory) return@withContext FileResult.Failed("$destination is not a folder")

        /*
         * A folder cannot be copied into itself.
         *
         * Without this the walk below descends into the copy it is making and runs
         * until the volume is full — the classic file-manager way to fill a device.
         */
        sources.firstOrNull { target.absolutePath.startsWith(it.absolutePath + File.separator) }
            ?.let { return@withContext FileResult.Invalid("Cannot copy ${it.name} into itself") }

        val total = sources.sumOf { it.sizeOnDisk() }
        var copied = 0L
        var failed = 0

        sources.forEach { source ->
            coroutineContext.ensureActive()
            val sameName = File(target, source.name)

            /*
             * Pasting something into the folder it already lives in.
             *
             * This destroyed the file, and it is worth spelling out because the
             * shape of it is not obvious: `File(target, source.name)` resolves to
             * the *source itself*, so the copy opened one stream for reading and
             * another for writing on the same path. Opening for write truncates,
             * so the read then found an empty file, reported nought bytes copied
             * and called it a success — and a move went on to delete what was left.
             * On a folder it did that to every child before removing the lot. The
             * hundreds of `MediaProvider: Deleted 1 items` lines in the log were
             * that, one per file.
             *
             * Compared by canonical path so `/sdcard` and `/storage/emulated/0`
             * are recognised as the same place, which on this device they are.
             */
            val sameFile = runCatching {
                sameName.canonicalPath == source.canonicalPath
            }.getOrDefault(false)

            if (sameFile && move) {
                // Already where it was asked to go. Not a failure, and emphatically
                // not something to copy onto itself first.
                copied += source.sizeOnDisk()
                onProgress(copied, total)
                return@forEach
            }

            /*
             * Never write over something already there.
             *
             * A paste that silently replaced a file of the same name would be the
             * one destructive act in this class with no confirmation in front of
             * it — including the case above, where the "existing file" is the
             * source. `Name (2)` costs the user a rename at worst; the alternative
             * costs them the file.
             */
            val landing = if (sameName.exists()) sameName.uniqueSibling() else sameName

            /*
             * A move within one volume is a rename, and a rename is instantaneous.
             *
             * The copy-then-delete path below exists because `renameTo` fails
             * across volumes — internal storage to a card — and returns false
             * rather than throwing. It does *not* fail within a volume, which is
             * most moves, and taking the slow path there meant reading and writing
             * every byte and then deleting the original file by file. Each of
             * those deletions is also a round trip to MediaProvider, which is what
             * made moving a large folder take minutes.
             */
            if (move && runCatching { source.renameTo(landing) }.getOrDefault(false)) {
                copied += source.sizeOnDisk()
                onProgress(copied, total)
                return@forEach
            }

            val landed = runCatching {
                source.copyInto(landing) { chunk ->
                    copied += chunk
                    onProgress(copied, total)
                }
            }.onFailure { error ->
                ThorLog.w(TAG, "Could not copy ${source.name}: ${error.message}")
            }.getOrDefault(false)

            if (!landed) {
                failed++
            } else if (move) {
                // Only after the copy has landed. Deleting first, or in the same
                // pass, is how a failed move loses the file outright.
                runCatching {
                    if (source.isDirectory) source.deleteRecursively() else source.delete()
                }
            }
        }

        when (failed) {
            0 -> FileResult.Done
            sources.size -> FileResult.Failed("Nothing could be copied")
            else -> FileResult.Failed("$failed of ${sources.size} could not be copied")
        }
    }

    // ---- Archives ----------------------------------------------------------

    /**
     * Zips [paths] into [archiveName] beside them.
     *
     * Zip and only zip. `ZipOutputStream` is in the platform, so this costs no
     * dependency and works on every device; 7z and rar would each be a library
     * and a format Android cannot open afterwards without another one.
     *
     * Written to a temporary file and renamed on success, so a copy interrupted
     * half way — the battery, the user, a full card — leaves no half-written
     * archive sitting in the folder looking like a real one.
     */
    suspend fun compress(
        paths: List<String>,
        destination: String,
        archiveName: String,
        onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FileResult = withContext(ioDispatcher) {
        val sources = paths.map(::File).filter(File::exists)
        if (sources.isEmpty()) return@withContext FileResult.Failed("Nothing left to compress")

        val name = archiveName.sanitized().ifEmpty { "Archive" }
        val target = File(destination, if (name.endsWith(ZIP, true)) name else "$name$ZIP")
        if (target.exists()) return@withContext FileResult.Invalid("${target.name} already exists")

        val total = sources.sumOf { it.sizeOnDisk() }
        var written = 0L
        val partial = File(destination, "${target.name}$PARTIAL")

        val result = runCatching {
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                sources.forEach { source ->
                    // The parent of the *source*, so a folder is stored with its
                    // own name at the root of the archive rather than with the
                    // whole path from the volume down.
                    source.addTo(zip, base = source.parentFile ?: File(destination)) { chunk ->
                        written += chunk
                        onProgress(written, total)
                    }
                }
            }
        }

        result.fold(
            onSuccess = {
                if (partial.renameTo(target)) {
                    FileResult.Done
                } else {
                    partial.delete()
                    FileResult.Failed("Could not finish ${target.name}")
                }
            },
            onFailure = { error ->
                partial.delete()
                ThorLog.w(TAG, "Could not compress into ${target.name}: ${error.message}")
                FileResult.Failed("Could not create ${target.name}")
            },
        )
    }

    /**
     * Unpacks a zip into a folder of its own, named after the archive.
     *
     * Into a folder rather than into the current directory, because an archive of
     * four hundred loose files emptied where you stood is not something a file
     * manager should do on one button press and cannot be undone in one either.
     */
    suspend fun extract(
        path: String,
        onProgress: (readBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FileResult = withContext(ioDispatcher) {
        val archive = File(path)
        if (!archive.isFile) return@withContext FileResult.Failed("${archive.name} is not there")
        if (!archive.extension.equals("zip", ignoreCase = true)) {
            return@withContext FileResult.Invalid("Only zip archives can be extracted")
        }

        val target = File(archive.parentFile, archive.nameWithoutExtension).uniqueSibling()
        if (!target.mkdirs()) return@withContext FileResult.Failed("Could not create ${target.name}")

        /*
         * Where an entry is allowed to land, resolved before anything is written.
         *
         * A zip entry's name is arbitrary text from whoever built the archive, and
         * `../../../` in it is a real attack — Zip Slip — that writes outside the
         * folder being extracted into. Every path is canonicalised and checked
         * against the destination, and the whole extraction fails rather than
         * skipping the bad entry: an archive containing one of these is not an
         * archive to trust the rest of.
         */
        val root = target.canonicalFile
        val total = archive.length().coerceAtLeast(1L)
        var read = 0L

        val result = runCatching {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val resolved = File(root, entry.name).canonicalFile

                    if (!resolved.path.startsWith(root.path + File.separator) && resolved != root) {
                        throw SecurityException("Entry escapes the destination: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        resolved.mkdirs()
                    } else {
                        resolved.parentFile?.mkdirs()
                        resolved.outputStream().buffered().use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val count = zip.read(buffer)
                                if (count <= 0) break
                                output.write(buffer, 0, count)
                                read += count
                                onProgress(read.coerceAtMost(total), total)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        result.fold(
            onSuccess = { FileResult.Done },
            onFailure = { error ->
                target.deleteRecursively()
                ThorLog.w(TAG, "Could not extract ${archive.name}: ${error.message}")
                when (error) {
                    is SecurityException ->
                        FileResult.Failed("${archive.name} tries to write outside its folder")

                    else -> FileResult.Failed("Could not extract ${archive.name}")
                }
            },
        )
    }

    /** Adds a file or a whole tree to an open archive, reporting each chunk. */
    private suspend fun File.addTo(zip: ZipOutputStream, base: File, onChunk: (Long) -> Unit) {
        coroutineContext.ensureActive()
        val relative = toRelativeString(base).replace(File.separatorChar, '/')

        if (isDirectory) {
            // A trailing slash is how a zip records an entry as a directory, which
            // is what keeps an empty folder in the archive at all.
            zip.putNextEntry(ZipEntry("$relative/"))
            zip.closeEntry()
            listFiles()?.forEach { it.addTo(zip, base, onChunk) }
            return
        }

        zip.putNextEntry(ZipEntry(relative))
        inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count <= 0) break
                zip.write(buffer, 0, count)
                onChunk(count.toLong())
            }
        }
        zip.closeEntry()
    }

    /** `Name`, then `Name (2)`, so extracting twice does not merge into the first. */
    private fun File.uniqueSibling(): File {
        if (!exists()) return this
        var attempt = 2
        while (attempt < MAX_NAME_ATTEMPTS) {
            val candidate = File(parentFile, "$name ($attempt)")
            if (!candidate.exists()) return candidate
            attempt++
        }
        return this
    }

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

    /** Total bytes underneath, for a progress denominator. */
    private fun File.sizeOnDisk(): Long =
        if (isDirectory) walkTopDown().filter(File::isFile).sumOf(File::length) else length()

    /**
     * Copies a file or a whole tree, reporting each chunk.
     *
     * Written out rather than using `copyRecursively`, which reports per *file* and
     * cannot say anything at all while a single large one is in flight.
     */
    private suspend fun File.copyInto(target: File, onChunk: (Long) -> Unit): Boolean {
        coroutineContext.ensureActive()

        if (isDirectory) {
            if (!target.exists() && !target.mkdirs()) return false
            listFiles()?.forEach { child ->
                if (!child.copyInto(File(target, child.name), onChunk)) return false
            }
            return true
        }

        inputStream().use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    onChunk(read.toLong())
                }
            }
        }
        return true
    }

    /** Strips what a filesystem cannot hold, so a bad name fails here and not deeper. */
    private fun String.sanitized(): String =
        trim().filterNot { it in ILLEGAL_NAME_CHARS }.take(MAX_NAME)

    private companion object {
        const val TAG = "Files"
        const val ROOT = "/storage"
        /**
         * Sixteen times the old 64 KB.
         *
         * Every chunk is a read syscall, a write syscall and a progress
         * callback, and on a gigabyte the old size made sixteen thousand
         * of each. Storage on this device reads far faster than that in
         * bulk, so the buffer was the thing setting the pace.
         */
        const val BUFFER = 1024 * 1024
        const val MAX_NAME = 255

        /** Above this, counting each subfolder's contents stops being free. */
        const val CHILD_COUNT_LIMIT = 250

        const val ZIP = ".zip"

        /** Suffix on a half-written archive, so it cannot be mistaken for a real one. */
        const val PARTIAL = ".part"

        /** A bound on the `Name (2)`, `Name (3)` search; past this something is wrong. */
        const val MAX_NAME_ATTEMPTS = 100

        /** Reserved on the filesystems Android mounts, FAT included. */
        val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        /** Entries under /storage that are not volumes the user cares about. */
        val NON_VOLUMES = setOf("self", "emulated", "enc_emulated")
    }
}

/** Where a listing can end up. */
sealed interface FileListing {
    data class Loaded(val path: String, val entries: List<FileEntry>) : FileListing

    /** The permission has not been granted; the explorer says so rather than showing nothing. */
    data object NoAccess : FileListing

    /** The path is gone — deleted underneath, or a card pulled out. */
    data object Missing : FileListing

    /** It is there and this process may not read it. */
    data object Unreadable : FileListing
}

/** What an operation did. Failures carry a sentence the user can act on. */
sealed interface FileResult {
    data object Done : FileResult

    /** The request itself was wrong — a name already taken, a folder inside itself. */
    data class Invalid(val reason: String) : FileResult

    data class Failed(val reason: String) : FileResult
}

data class FileShortcut(val label: String, val path: String)

data class VolumeSpace(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}
