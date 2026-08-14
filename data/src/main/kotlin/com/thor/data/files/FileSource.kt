package com.thor.data.files

import com.thor.core.model.FileEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * Somewhere the explorer can browse.
 *
 * The explorer was built directly on `java.io.File`, which was the right shape
 * for exactly as long as everything it touched was on the device. A network share
 * is not, and the difference is not a detail that can be hidden behind a path
 * string: there is no `renameTo` across a wire, no `canonicalPath`, no `StatFs`,
 * and the cost of asking a directory how many children it has goes from a
 * microsecond to a round trip.
 *
 * So the repository dispatches to one of these on the path's scheme, and each
 * answers for its own storage. What the *repository* still owns is everything
 * built on top: copying, moving, zipping and extracting are written once against
 * this interface and work between any two sources — which is the whole point,
 * because "move this folder from the card onto the NAS" is the thing a user
 * actually wants and neither source could implement alone.
 *
 * Every method suspends. On a share that is not a nicety; it is a network call.
 */
interface FileSource {

    /** Whether [path] belongs to this source. Tested in order; local answers last. */
    fun handles(path: String): Boolean

    /** Places worth offering in the rail. May be empty. */
    suspend fun shortcuts(): List<FileShortcut>

    suspend fun list(path: String): FileListing

    suspend fun entryAt(path: String): FileEntry?

    /** The parent of [path], or null at the top of this source's tree. */
    fun parentOf(path: String): String?

    /**
     * [name] inside [parent], joined the way this source spells a path.
     *
     * A string operation rather than a lookup — it is called once per file during
     * a copy and must not touch the network.
     */
    fun childPath(parent: String, name: String): String

    /** The last segment of [path], with no trailing separator. */
    fun nameOf(path: String): String

    suspend fun volumeSpace(path: String): VolumeSpace?

    // ---- Changing things ---------------------------------------------------

    suspend fun createDirectory(parent: String, name: String): FileResult

    suspend fun rename(path: String, newName: String): FileResult

    suspend fun delete(paths: List<String>): FileResult

    // ---- What transfers and archives are built from ------------------------
    //
    // Deliberately small and mechanical. Anything with a policy in it — never
    // overwriting, counting bytes rather than files, refusing to copy a folder
    // into itself — belongs in the repository, written once, rather than in each
    // source where two implementations would eventually disagree about it.

    suspend fun exists(path: String): Boolean

    suspend fun isDirectory(path: String): Boolean

    /**
     * Child paths, or null when [path] cannot be read as a directory.
     *
     * Empty and unreadable must stay different. Treating an I/O failure as an
     * empty folder lets a move copy an empty shell and then delete the populated
     * source, which is data loss.
     */
    suspend fun children(path: String): List<String>?

    /** Total bytes underneath [path], for a progress denominator. */
    suspend fun sizeOnDisk(path: String): Long

    suspend fun openRead(path: String): InputStream

    /** Creates or truncates [path] and opens it for writing. */
    suspend fun openWrite(path: String): OutputStream

    suspend fun mkdirs(path: String): Boolean

    /** Deletes [path], recursively when it is a directory. */
    suspend fun deleteTree(path: String): Boolean

    /**
     * Moves [from] to [to] without copying the bytes, or false if it cannot.
     *
     * False is not a failure — it means "not possible here", and the caller falls
     * back to copy-then-delete. Across two volumes, or two servers, that is the
     * only way; within one it would be a waste of every byte.
     */
    suspend fun moveWithin(from: String, to: String): Boolean

    /**
     * A value equal for two paths naming the same thing, for the same-file guard.
     *
     * Needed because pasting something into the folder it already lives in used
     * to destroy it, and the check that stops it can only be written in terms of
     * identity — `/sdcard/x` and `/storage/emulated/0/x` are one file wearing two
     * names. Locally that is the canonical path; on a share the URL already is
     * canonical, so it is the path itself.
     */
    suspend fun identityOf(path: String): String
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

    /**
     * A share that could not be reached, with the reason.
     *
     * Distinct from [Unreadable], which is a permission answer from storage that
     * is definitely present. A server is a different kind of absent — it may be
     * asleep, off the network, or refusing the password — and each of those has a
     * different next step for the user, so the sentence is carried rather than
     * flattened into "cannot read".
     */
    data class Offline(val reason: String) : FileListing
}

/** What an operation did. Failures carry a sentence the user can act on. */
sealed interface FileResult {
    data object Done : FileResult

    /** The request itself was wrong — a name already taken, a folder inside itself. */
    data class Invalid(val reason: String) : FileResult

    data class Failed(val reason: String) : FileResult
}

data class FileShortcut(
    val label: String,
    val path: String,
    /**
     * Whether this is a network share, so the rail can say so.
     *
     * A share behaves differently enough to be worth marking: it can be
     * unreachable, it is slower, and a copy onto it is a real transfer rather
     * than a filesystem operation.
     */
    val remote: Boolean = false,
)

data class VolumeSpace(val freeBytes: Long, val totalBytes: Long) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
}
