package com.thor.data.files

import com.thor.core.common.dispatchers.Dispatcher
import com.thor.core.common.dispatchers.ThorDispatcher
import com.thor.core.common.log.ThorLog
import com.thor.core.model.FileEntry
import com.thor.core.model.SmbServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Storage, as the explorer sees it.
 *
 * Two jobs, and they are worth separating in the reader's head. Most of what is
 * here is dispatch: a path names either the device or a network share, and the
 * question goes to whichever [FileSource] owns it. The rest — copying, moving,
 * zipping, extracting — is written *once*, against the interface, and is the
 * reason the interface exists at all. "Move this folder from the card onto the
 * NAS" is what a person actually wants from a file manager with shares in it, and
 * neither source can implement that alone: the bytes come out of one and go into
 * the other.
 *
 * Everything suspends and moves to IO once, here. Listing a directory of ten
 * thousand ROMs is tens of milliseconds of `stat` calls; a listing over SMB is a
 * network round trip. Both are several dropped frames on the main thread.
 */
@Singleton
class FileRepository @Inject constructor(
    private val local: LocalFileSource,
    private val remote: SmbFileSource,
    /** Only for the settings page's "scan the network"; nothing here uses it. */
    private val discovery: SmbDiscovery,
    @Dispatcher(ThorDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Which source owns [path].
     *
     * Ordered, with local last: it claims everything, because a path with no
     * recognised scheme is a path on this device and always has been.
     */
    private fun sourceFor(path: String): FileSource =
        if (remote.handles(path)) remote else local

    /** See [LocalFileSource.hasStorageAccess]. Shares need no such permission. */
    val hasStorageAccess: Boolean get() = local.hasStorageAccess

    /** Where the explorer opens: the user's own storage, not the filesystem root. */
    fun defaultDirectory(): String = local.defaultDirectory()

    /**
     * The places worth offering as a starting point.
     *
     * Volumes first, then shares. A share that is switched off is still listed —
     * it is a place the user set up, and hiding it because the server is asleep
     * would make the rail change shape depending on what is powered on.
     */
    suspend fun shortcuts(): List<FileShortcut> = withContext(ioDispatcher) {
        local.shortcuts() + remote.shortcuts()
    }

    suspend fun list(path: String): FileListing = withContext(ioDispatcher) {
        sourceFor(path).list(path)
    }

    /** The parent of [path], or null at the top of its tree. */
    fun parentOf(path: String): String? = sourceFor(path).parentOf(path)

    suspend fun entryAt(path: String): FileEntry? = withContext(ioDispatcher) {
        sourceFor(path).entryAt(path)
    }

    /** Free and total bytes on whatever holds [path], for the details pane. */
    suspend fun volumeSpace(path: String): VolumeSpace? = withContext(ioDispatcher) {
        sourceFor(path).volumeSpace(path)
    }

    /** Checks a share is reachable, for the settings page that configures it. */
    suspend fun testServer(server: SmbServer): String = withContext(ioDispatcher) {
        remote.probe(server)
    }

    /** Asks the network what file servers are on it; see [SmbDiscovery]. */
    suspend fun discoverServers(): List<DiscoveredServer> = discovery.scan()

    // ---- Changing things ---------------------------------------------------

    suspend fun createDirectory(parent: String, name: String): FileResult =
        withContext(ioDispatcher) { sourceFor(parent).createDirectory(parent, name) }

    /**
     * Renames in place.
     *
     * Within the same directory only, which is what "rename" means to a user;
     * moving is [transfer] and has to handle crossing volumes — and now servers.
     */
    suspend fun rename(path: String, newName: String): FileResult =
        withContext(ioDispatcher) { sourceFor(path).rename(path, newName) }

    /**
     * Deletes, recursively for a directory.
     *
     * No trash. Android gives an app no shared wastebasket to move a file into, and
     * a private one would silently consume the user's storage while telling them
     * they had freed it. So this is permanent, and the surface asking for it is
     * responsible for saying so first.
     *
     * Grouped by source so a mixed selection — two files on the card and one on
     * the NAS — is still one call each rather than one per file.
     */
    suspend fun delete(paths: List<String>): FileResult = withContext(ioDispatcher) {
        val bySource = paths.groupBy(::sourceFor)
        val results = bySource.map { (source, owned) -> source.delete(owned) }

        when {
            results.all { it is FileResult.Done } -> FileResult.Done
            results.none { it is FileResult.Done } -> FileResult.Failed("Nothing could be deleted")
            else -> FileResult.Failed("Some items could not be deleted")
        }
    }

    /**
     * Copies into [destination], reporting progress as it goes.
     *
     * Byte-counted rather than file-counted: a folder of one 8 GB disc image and
     * four hundred saves is one file away from finished for almost the whole
     * operation if you count files, which makes the bar a lie exactly when the user
     * is watching it. Over a share that matters more, not less — the transfer is
     * slow enough to be watched.
     *
     * @param move deletes each source once its copy has landed.
     */
    suspend fun transfer(
        paths: List<String>,
        destination: String,
        move: Boolean,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FileResult = withContext(ioDispatcher) {
        val target = sourceFor(destination)
        if (!target.isDirectory(destination)) {
            return@withContext FileResult.Failed("$destination is not a folder")
        }

        val jobs = paths
            .map { Move(sourceFor(it), it) }
            .filter { it.source.exists(it.path) }
        if (jobs.isEmpty()) return@withContext FileResult.Failed("Nothing left to copy")

        /*
         * A folder cannot be copied into itself.
         *
         * Without this the walk below descends into the copy it is making and runs
         * until the volume is full — the classic file-manager way to fill a device.
         * Only ever asked within one source: `smb://tower/games` is not inside
         * `/storage/emulated/0/games` however similar the two strings look.
         */
        jobs.forEach { job ->
            if (job.source === target && target.contains(job.path, destination)) {
                return@withContext FileResult.Invalid("Cannot copy ${job.name} into itself")
            }
        }

        val sizes = jobs.map { it.source.sizeOnDisk(it.path) }
        val total = sizes.sum()
        var copied = 0L
        var failed = 0

        jobs.forEachIndexed { index, job ->
            coroutineContext.ensureActive()
            val size = sizes[index]
            val sameName = target.childPath(destination, job.name)

            /*
             * Pasting something into the folder it already lives in.
             *
             * This destroyed the file, and it is worth spelling out because the
             * shape of it is not obvious: the destination resolved to the *source
             * itself*, so the copy opened one stream for reading and another for
             * writing on the same path. Opening for write truncates, so the read
             * then found an empty file, reported nought bytes copied and called it
             * a success — and a move went on to delete what was left. On a folder
             * it did that to every child before removing the lot.
             *
             * Compared by the source's own notion of identity, so `/sdcard` and
             * `/storage/emulated/0` are recognised as the same place — which on
             * this device they are.
             */
            val sameFile = job.source === target &&
                runCatching {
                    target.identityOf(sameName) == job.source.identityOf(job.path)
                }.getOrDefault(false)

            if (sameFile && move) {
                // Already where it was asked to go. Not a failure, and emphatically
                // not something to copy onto itself first.
                copied += size
                onProgress(copied, total)
                return@forEachIndexed
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
            val landing = if (target.exists(sameName)) {
                target.uniqueChild(destination, job.name)
            } else {
                sameName
            }

            /*
             * A move that does not touch the bytes.
             *
             * Within one volume this is a rename, and instantaneous; within one
             * SMB share it is a rename on the server, which is the same win over a
             * far slower wire. It is refused across volumes, across shares and
             * between the two sources — [FileSource.moveWithin] answers false
             * there, and the copy-and-delete below is the only way.
             *
             * The slow path is not merely slower: every deletion it performs is a
             * round trip to MediaProvider, which is what made moving a large folder
             * take minutes.
             */
            if (move && job.source === target && job.source.moveWithin(job.path, landing)) {
                copied += size
                onProgress(copied, total)
                return@forEachIndexed
            }

            val landed = runCatching {
                copyTree(job.source, job.path, target, landing) { chunk ->
                    copied += chunk
                    onProgress(copied, total)
                }
            }.onFailure { error ->
                ThorLog.w(TAG, "Could not copy ${job.name}: ${error.message}")
            }.getOrDefault(false)

            if (!landed) {
                failed++
            } else if (move) {
                // Only after the copy has landed. Deleting first, or in the same
                // pass, is how a failed move loses the file outright.
                job.source.deleteTree(job.path)
            }
        }

        when (failed) {
            0 -> FileResult.Done
            jobs.size -> FileResult.Failed("Nothing could be copied")
            else -> FileResult.Failed("$failed of ${jobs.size} could not be copied")
        }
    }

    // ---- Archives ----------------------------------------------------------

    /**
     * Zips [paths] into [archiveName] in [destination].
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
        val target = sourceFor(destination)
        val jobs = paths
            .map { Move(sourceFor(it), it) }
            .filter { it.source.exists(it.path) }
        if (jobs.isEmpty()) return@withContext FileResult.Failed("Nothing left to compress")

        val name = archiveName.sanitized().ifEmpty { "Archive" }
        val fileName = if (name.endsWith(ZIP, ignoreCase = true)) name else "$name$ZIP"
        val archive = target.childPath(destination, fileName)
        if (target.exists(archive)) {
            return@withContext FileResult.Invalid("$fileName already exists")
        }

        val total = jobs.sumOf { it.source.sizeOnDisk(it.path) }
        var written = 0L
        val partial = target.childPath(destination, "$fileName$PARTIAL")

        val result = runCatching {
            ZipOutputStream(target.openWrite(partial).buffered()).use { zip ->
                jobs.forEach { job ->
                    // No prefix, so a folder is stored with its own name at the
                    // root of the archive rather than with the whole path from the
                    // volume down.
                    addTo(job.source, job.path, zip, prefix = "") { chunk ->
                        written += chunk
                        onProgress(written, total)
                    }
                }
            }
        }

        result.fold(
            onSuccess = {
                if (target.moveWithin(partial, archive)) {
                    FileResult.Done
                } else {
                    target.deleteTree(partial)
                    FileResult.Failed("Could not finish $fileName")
                }
            },
            onFailure = { error ->
                target.deleteTree(partial)
                ThorLog.w(TAG, "Could not compress into $fileName: ${error.message}")
                FileResult.Failed("Could not create $fileName")
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
        val source = sourceFor(path)
        val name = source.nameOf(path)

        if (!source.exists(path) || source.isDirectory(path)) {
            return@withContext FileResult.Failed("$name is not there")
        }
        if (!name.endsWith(ZIP, ignoreCase = true)) {
            return@withContext FileResult.Invalid("Only zip archives can be extracted")
        }

        val parent = source.parentOf(path)
            ?: return@withContext FileResult.Failed("There is nowhere to unpack $name")

        val target = source.uniqueChild(parent, name.substringBeforeLast('.'))
        if (!source.mkdirs(target)) {
            return@withContext FileResult.Failed("Could not create ${source.nameOf(target)}")
        }

        val total = source.sizeOnDisk(path).coerceAtLeast(1L)
        var read = 0L

        val result = runCatching {
            ZipInputStream(source.openRead(path).buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    val segments = entry.name.safeSegments()

                    if (entry.isDirectory) {
                        source.mkdirs(segments.fold(target, source::childPath))
                    } else {
                        val folder = segments.dropLast(1).fold(target, source::childPath)
                        source.mkdirs(folder)
                        source.openWrite(source.childPath(folder, segments.last())).use { output ->
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
                source.deleteTree(target)
                ThorLog.w(TAG, "Could not extract $name: ${error.message}")
                when (error) {
                    is SecurityException -> FileResult.Failed("$name tries to write outside its folder")
                    else -> FileResult.Failed("Could not extract $name")
                }
            },
        )
    }

    // ---- The engine --------------------------------------------------------

    /**
     * Copies a file or a whole tree between any two sources, reporting each chunk.
     *
     * Written out rather than using `copyRecursively`, which is `java.io.File`
     * only, reports per *file*, and cannot say anything at all while a single
     * large one is in flight.
     */
    private suspend fun copyTree(
        from: FileSource,
        source: String,
        to: FileSource,
        target: String,
        onChunk: (Long) -> Unit,
    ): Boolean {
        coroutineContext.ensureActive()

        if (from.isDirectory(source)) {
            if (!to.mkdirs(target)) return false
            from.children(source).forEach { child ->
                val landing = to.childPath(target, from.nameOf(child))
                if (!copyTree(from, child, to, landing, onChunk)) return false
            }
            return true
        }

        from.openRead(source).use { input ->
            to.openWrite(target).use { output ->
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

    /** Adds a file or a whole tree to an open archive, reporting each chunk. */
    private suspend fun addTo(
        source: FileSource,
        path: String,
        zip: ZipOutputStream,
        prefix: String,
        onChunk: (Long) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val entryName = prefix + source.nameOf(path)

        if (source.isDirectory(path)) {
            // A trailing slash is how a zip records an entry as a directory, which
            // is what keeps an empty folder in the archive at all.
            zip.putNextEntry(ZipEntry("$entryName/"))
            zip.closeEntry()
            source.children(path).forEach { addTo(source, it, zip, "$entryName/", onChunk) }
            return
        }

        zip.putNextEntry(ZipEntry(entryName))
        source.openRead(path).use { input ->
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

    /**
     * Whether [destination] is [path] itself, or sits somewhere underneath it.
     *
     * A walk up the destination's parents rather than a prefix test on the two
     * strings, which is what this was until a Windows JVM found the hole in it:
     * the separator is not always `/`, so `Saves\inside`.startsWith(`Saves/`) is
     * false and the guard silently stopped guarding — on the one test written to
     * prove it could not.
     *
     * Comparing identities also catches what a prefix test never could:
     * `/sdcard/Saves` and `/storage/emulated/0/Saves` are one folder written two
     * ways, and on this device both are reachable.
     *
     * Bounded, because a symlink loop on local storage would otherwise walk for
     * ever — and this runs before a copy that has not started reporting progress.
     */
    private suspend fun FileSource.contains(path: String, destination: String): Boolean {
        val root = identityOf(path)
        var here: String? = destination
        var depth = 0

        while (here != null && depth < MAX_TREE_DEPTH) {
            if (identityOf(here) == root) return true
            here = parentOf(here)
            depth++
        }
        return false
    }

    /** `Name`, then `Name (2)`, so extracting twice does not merge into the first. */
    private suspend fun FileSource.uniqueChild(parent: String, name: String): String {
        val first = childPath(parent, name)
        if (!exists(first)) return first

        var attempt = 2
        while (attempt < MAX_NAME_ATTEMPTS) {
            val candidate = childPath(parent, "$name ($attempt)")
            if (!exists(candidate)) return candidate
            attempt++
        }
        return first
    }

    /** One thing being copied, with the source that owns it. */
    private data class Move(val source: FileSource, val path: String) {
        val name: String get() = source.nameOf(path)
    }

    private companion object {
        const val TAG = "Files"

        /**
         * Sixteen times the old 64 KB.
         *
         * Every chunk is a read syscall, a write syscall and a progress
         * callback, and on a gigabyte the old size made sixteen thousand
         * of each. Storage on this device reads far faster than that in
         * bulk, so the buffer was the thing setting the pace — and over SMB
         * a large read is worth even more, because each one is a round trip.
         */
        const val BUFFER = 1024 * 1024

        const val ZIP = ".zip"

        /** Suffix on a half-written archive, so it cannot be mistaken for a real one. */
        const val PARTIAL = ".part"

        /** A bound on the `Name (2)`, `Name (3)` search; past this something is wrong. */
        const val MAX_NAME_ATTEMPTS = 100

        /** Deeper than any real tree, and a backstop against a symlink loop. */
        const val MAX_TREE_DEPTH = 64
    }
}

/**
 * A zip entry's path, checked before anything is written.
 *
 * An entry's name is arbitrary text from whoever built the archive, and `../../`
 * in it is a real attack — Zip Slip — that writes outside the folder being
 * extracted into. The whole extraction fails rather than the bad entry being
 * skipped: an archive containing one of these is not an archive to trust the rest
 * of.
 *
 * A check on the *name* rather than on the resolved path, which is what it used
 * to be. Canonicalising is a `java.io.File` idea and there is nothing to
 * canonicalise against on a share — and it is not needed, because the only way a
 * resolved path could land outside a freshly created folder is a `..` segment or
 * an absolute name, and both are refused here. Backslashes are folded first, so
 * an archive built on Windows cannot smuggle a separator past the split.
 */
private fun String.safeSegments(): List<String> {
    if (startsWith('/') || startsWith('\\')) {
        throw SecurityException("Entry is an absolute path: $this")
    }

    val segments = replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }

    if (segments.isEmpty()) throw SecurityException("Entry has no name")
    if (segments.any { it == ".." }) {
        throw SecurityException("Entry escapes the destination: $this")
    }
    return segments
}
