package com.thor.data.files

import com.thor.core.common.log.ThorLog
import com.thor.core.model.FileEntry
import com.thor.core.model.SMB_SCHEME
import com.thor.core.model.SmbServer
import com.thor.core.model.isRemotePath
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileInputStream
import jcifs.smb.SmbFileOutputStream
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.net.UnknownHostException
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Windows and NAS shares, over SMB2 and SMB3.
 *
 * jcifs-ng rather than the original jcifs, which only speaks SMB1 — a protocol
 * Windows has shipped disabled since 2017 and that every NAS worth connecting to
 * has followed suit on. Its `SmbFile` is deliberately shaped like `java.io.File`,
 * which is what makes this implementable at all without a second copy engine.
 *
 * ## Paths
 *
 * `smb://host/share/folder/file.iso`, which is jcifs's own URL form, so a path
 * the explorer holds is a path the library takes verbatim. Directories always
 * carry a trailing slash: jcifs treats `smb://tower/games` as a *file* called
 * `games` and refuses to enumerate it, so every path that names a directory is
 * normalised on the way in.
 *
 * `smb://host/` is the server itself, and listing it enumerates the shares it
 * offers. That is why a share is not part of [SmbServer] — browsing to it is a
 * keypress rather than a field to fill in correctly.
 *
 * ## Names
 *
 * Hostnames and IP addresses only. NetBIOS name resolution is switched off
 * deliberately: it works by broadcasting on port 137 and waiting, which on a
 * mobile network or a Wi-Fi network with client isolation is several seconds of
 * silence per lookup before the connection is even attempted. `resolveOrder=DNS`
 * makes an unreachable name fail in milliseconds instead.
 */
@Singleton
class SmbFileSource @Inject constructor(
    private val directory: SmbServerDirectory,
) : FileSource {

    /**
     * One authenticated context per server, kept because building one is not free.
     *
     * Keyed by the credentials rather than by the server id, so editing a password
     * invalidates the entry instead of leaving a context authenticated as whoever
     * the user just stopped being — the single most confusing possible outcome of
     * fixing a typo.
     */
    private val contexts = HashMap<String, CIFSContext>()

    override fun handles(path: String): Boolean = isRemotePath(path)

    /** One rail entry per configured server, reachable or not. */
    override suspend fun shortcuts(): List<FileShortcut> =
        directory.servers().filter(SmbServer::isUsable).map { server ->
            FileShortcut(
                label = server.displayName,
                path = server.rootPath,
                remote = true,
            )
        }

    override suspend fun list(path: String): FileListing {
        val url = directoryUrl(path)
        val context = contextFor(url) ?: return FileListing.Offline("No server is set up for this address")

        return try {
            val children = SmbFile(url, context).listFiles() ?: return FileListing.Unreadable
            val entries = buildList(children.size) {
                children.forEach { child ->
                    coroutineContext.ensureActive()
                    add(child.toEntry())
                }
            }
            FileListing.Loaded(path = url, entries = entries)
        } catch (e: SmbAuthException) {
            ThorLog.w(TAG, "Refused at $url: ${e.message}")
            FileListing.Offline("The server refused these credentials")
        } catch (e: SmbException) {
            ThorLog.w(TAG, "Cannot list $url: ${e.message}")
            FileListing.Offline(e.readable())
        } catch (e: UnknownHostException) {
            FileListing.Offline("That server name could not be found on the network")
        } catch (e: Exception) {
            ThorLog.w(TAG, "Cannot list $url: ${e.message}")
            FileListing.Offline("The server could not be reached")
        }
    }

    override suspend fun entryAt(path: String): FileEntry? = withFile(path) { it.toEntry() }

    /**
     * Up one level, stopping at the server rather than at `smb://`.
     *
     * Returning null at `smb://host/` is what makes Back leave the explorer's
     * remote tree instead of navigating to a scheme with no listing behind it.
     */
    override fun parentOf(path: String): String? {
        val body = path.removePrefix(SMB_SCHEME).trim('/')
        if (body.isEmpty()) return null
        val segments = body.split('/').filter(String::isNotEmpty)
        // One segment is the host on its own, which is already the top.
        if (segments.size <= 1) return null
        return SMB_SCHEME + segments.dropLast(1).joinToString("/") + "/"
    }

    override fun childPath(parent: String, name: String): String = directoryUrl(parent) + name

    override fun nameOf(path: String): String =
        path.trimEnd('/').substringAfterLast('/')

    /**
     * How full the share is, when the server will say.
     *
     * `length()` on a share root is its capacity and `getDiskFreeSpace()` is what
     * is left. Both are one round trip, and a server that declines to answer gets
     * a null rather than a zero — the storage bar draws nothing for null, and
     * "0 bytes free" would be a claim rather than an absence.
     */
    override suspend fun volumeSpace(path: String): VolumeSpace? {
        val shareRoot = shareRootOf(path) ?: return null
        return withFile(shareRoot) { file ->
            val free = file.diskFreeSpace
            val total = file.length()
            if (total <= 0L) null else VolumeSpace(freeBytes = free, totalBytes = total)
        }
    }

    // ---- Changing things ---------------------------------------------------

    override suspend fun createDirectory(parent: String, name: String): FileResult {
        val clean = name.sanitized()
        if (clean.isEmpty()) return FileResult.Invalid("That name cannot be used")

        val target = childPath(parent, clean)
        return attempt("Could not create $clean") {
            val context = contextFor(target) ?: return@attempt FileResult.Failed(NO_SERVER)
            val file = SmbFile(directoryUrl(target), context)
            if (file.exists()) return@attempt FileResult.Invalid("$clean already exists")
            file.mkdirs()
            FileResult.Done
        }
    }

    override suspend fun rename(path: String, newName: String): FileResult {
        val clean = newName.sanitized()
        if (clean.isEmpty()) return FileResult.Invalid("That name cannot be used")

        val parent = parentOf(path) ?: return FileResult.Failed("Nothing to rename here")
        val target = childPath(parent, clean)

        return attempt("Could not rename ${nameOf(path)}") {
            val context = contextFor(path) ?: return@attempt FileResult.Failed(NO_SERVER)
            val source = SmbFile(path, context)
            if (!source.exists()) return@attempt FileResult.Failed("${nameOf(path)} is no longer there")

            val destination = SmbFile(if (source.isDirectory) directoryUrl(target) else target, context)
            if (destination.exists()) return@attempt FileResult.Invalid("$clean already exists")

            source.renameTo(destination)
            FileResult.Done
        }
    }

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

    override suspend fun exists(path: String): Boolean = withFile(path) { it.exists() } ?: false

    override suspend fun isDirectory(path: String): Boolean =
        withFile(path) { it.isDirectory } ?: false

    override suspend fun children(path: String): List<String>? =
        withFile(directoryUrl(path)) { file ->
            file.listFiles()?.map { it.normalisedPath() }
        }

    /**
     * Bytes underneath, which on a share costs a listing per directory.
     *
     * Done anyway, because the progress bar is byte-counted and a copy onto a NAS
     * is precisely where the user is going to sit and watch it. The walk is one
     * round trip per folder, against a transfer that is about to be one per
     * megabyte.
     */
    override suspend fun sizeOnDisk(path: String): Long {
        val file = fileAt(path) ?: return 0L
        return runCatching { file.sizeOf() }.getOrDefault(0L)
    }

    private suspend fun SmbFile.sizeOf(): Long {
        coroutineContext.ensureActive()
        if (!isDirectory) return length()
        return listFiles()?.sumOf { it.sizeOf() } ?: 0L
    }

    override suspend fun openRead(path: String): InputStream =
        SmbFileInputStream(requireFile(path))

    override suspend fun openWrite(path: String): OutputStream =
        SmbFileOutputStream(requireFile(path))

    override suspend fun mkdirs(path: String): Boolean = runCatching {
        val file = requireFile(directoryUrl(path))
        file.isDirectory || run { file.mkdirs(); true }
    }.getOrDefault(false)

    /**
     * Deletes, recursively for a directory.
     *
     * jcifs's own `delete()` already walks a directory's contents, so this is one
     * call rather than a hand-written recursion — and a hand-written one would be
     * a round trip per level on top of the ones the library is already making.
     */
    override suspend fun deleteTree(path: String): Boolean = runCatching {
        val file = requireFile(path)
        if (!file.exists()) return@runCatching true
        file.delete()
        true
    }.onFailure {
        ThorLog.w(TAG, "Could not delete $path: ${it.message}")
    }.getOrDefault(false)

    /**
     * A rename on the server, which is instant — but only within one share.
     *
     * Across two shares, or two servers, SMB has no server-side move at all: the
     * bytes have to come down to the device and go back up. Returning false there
     * is what sends the repository down its copy-then-delete path.
     */
    override suspend fun moveWithin(from: String, to: String): Boolean {
        if (shareRootOf(from) == null || shareRootOf(from) != shareRootOf(to)) return false
        return runCatching {
            val context = contextFor(from) ?: return false
            val source = SmbFile(from, context)
            val destination = SmbFile(if (source.isDirectory) directoryUrl(to) else to, context)
            if (!source.exists() || destination.exists()) return@runCatching false
            source.renameTo(destination)
            true
        }.getOrDefault(false)
    }

    /** The URL is already canonical; only the trailing slash varies. */
    override suspend fun identityOf(path: String): String =
        path.trimEnd('/').lowercase(Locale.ROOT)

    // ---- Reaching a server -------------------------------------------------

    /**
     * Checks a server is reachable and says what went wrong when it is not.
     *
     * Its own method rather than a call to [list] whose result is inspected,
     * because the settings page asks a different question: not "what is in here"
     * but "did this work", and the interesting answers are all failures.
     */
    suspend fun probe(server: SmbServer): String {
        if (!server.isUsable) return "Enter the server's name or address first"

        val context = runCatching { contextFor(server) }.getOrNull()
            ?: return "Those settings could not be used to connect"

        return try {
            val root = SmbFile(directoryUrl(server.rootPath), context)
            val children = root.listFiles()
            val count = children?.size ?: 0
            if (server.share.isBlank()) {
                "Connected — $count ${if (count == 1) "share" else "shares"}"
            } else {
                "Connected — $count ${if (count == 1) "item" else "items"} in ${server.share}"
            }
        } catch (e: SmbAuthException) {
            "Refused: check the username and password"
        } catch (e: UnknownHostException) {
            "No server of that name answered"
        } catch (e: SmbException) {
            e.readable()
        } catch (e: Exception) {
            ThorLog.w(TAG, "Probe of ${server.host} failed: ${e.message}")
            "Could not reach ${server.host}"
        }
    }

    /** The configured server whose host matches [path], if there is one. */
    private suspend fun serverFor(path: String): SmbServer? {
        val host = path.removePrefix(SMB_SCHEME).substringBefore('/').lowercase()
        if (host.isEmpty()) return null
        return directory.servers().firstOrNull { it.host.trim().lowercase() == host }
    }

    private suspend fun contextFor(path: String): CIFSContext? =
        serverFor(path)?.let(::contextFor)

    /**
     * The authenticated context for [server], built once and kept.
     *
     * Synchronised because a listing and a copy can want the same server at the
     * same moment, and two threads racing to build one would leave the map holding
     * whichever finished last while the other kept a context nothing else uses.
     */
    @Synchronized
    private fun contextFor(server: SmbServer): CIFSContext {
        val key = listOf(
            server.host, server.username, server.password, server.domain, server.guest,
        ).joinToString(" ")

        contexts[key]?.let { return it }

        val base = BaseContext(PropertyConfiguration(smbProperties()))
        val context = if (server.guest || server.username.isBlank()) {
            /*
             * A null session rather than the literal user "guest".
             *
             * Both are called guest access and they are different requests: this
             * one authenticates as nobody, which is what an open share on a NAS
             * accepts. Anyone whose server wants the named guest account can type
             * `guest` into the username field, which is the same two keystrokes
             * as a switch would have been.
             */
            base.withAnonymousCredentials()
        } else {
            base.withCredentials(
                NtlmPasswordAuthenticator(server.domain, server.username, server.password),
            )
        }

        contexts[key] = context
        return context
    }

    /**
     * How jcifs is configured, and every line is here for a reason.
     *
     * The defaults are tuned for a Windows desktop on a wired LAN, which is not
     * this device: a handheld's Wi-Fi drops, sleeps and roams, and jcifs's default
     * timeouts are long enough that a share which has gone away would hang the
     * explorer for the better part of a minute before admitting it.
     */
    private fun smbProperties() = Properties().apply {
        // SMB1 is off everywhere that matters and is a liability where it is not.
        setProperty("jcifs.smb.client.minVersion", "SMB202")
        setProperty("jcifs.smb.client.maxVersion", "SMB311")

        // See the class note: NetBIOS resolution is a broadcast-and-wait that
        // cannot work on most networks this device is on.
        setProperty("jcifs.resolveOrder", "DNS")

        /*
         * DFS off.
         *
         * A referral points at a hostname the server believes in, which on a home
         * network is regularly a name nothing can resolve — and jcifs follows it
         * before serving the request, so a share that works perfectly in Windows
         * hangs here until the lookup gives up. Nothing this explorer does needs
         * a namespace.
         */
        setProperty("jcifs.smb.client.dfs.disabled", "true")

        setProperty("jcifs.smb.client.connTimeout", CONNECT_TIMEOUT_MS.toString())
        setProperty("jcifs.smb.client.responseTimeout", RESPONSE_TIMEOUT_MS.toString())
        setProperty("jcifs.smb.client.soTimeout", SOCKET_TIMEOUT_MS.toString())

        // Signing is negotiated, not demanded: a NAS that does not offer it is
        // still a NAS the user asked to browse.
        setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
    }

    private suspend fun fileAt(path: String): SmbFile? {
        val context = contextFor(path) ?: return null
        return runCatching { SmbFile(path, context) }.getOrNull()
    }

    /** As [fileAt], but throwing — for the stream methods, whose callers catch. */
    private suspend fun requireFile(path: String): SmbFile {
        val context = contextFor(path) ?: throw SmbException(NO_SERVER, null)
        return SmbFile(path, context)
    }

    private suspend fun <T> withFile(path: String, block: (SmbFile) -> T): T? =
        runCatching { fileAt(path)?.let(block) }
            .onFailure { ThorLog.w(TAG, "$path: ${it.message}") }
            .getOrNull()

    private suspend fun attempt(failure: String, block: suspend () -> FileResult): FileResult =
        try {
            block()
        } catch (e: SmbAuthException) {
            FileResult.Failed("The server refused these credentials")
        } catch (e: SmbException) {
            ThorLog.w(TAG, "$failure: ${e.message}")
            FileResult.Failed(e.readable())
        } catch (e: Exception) {
            ThorLog.w(TAG, "$failure: ${e.message}")
            FileResult.Failed(failure)
        }

    // ---- Paths -------------------------------------------------------------

    /** `smb://host/share/`, or null when [path] does not name one. */
    private fun shareRootOf(path: String): String? {
        val segments = path.removePrefix(SMB_SCHEME).trim('/').split('/').filter(String::isNotEmpty)
        if (segments.size < 2) return null
        return "$SMB_SCHEME${segments[0]}/${segments[1]}/"
    }

    private fun SmbFile.normalisedPath(): String =
        if (isDirectory) directoryUrl(path) else path.trimEnd('/')

    /**
     * What a listing row carries.
     *
     * No child count. It is one round trip per subdirectory, and a folder of
     * twenty on a sleeping NAS is twenty waits for a number nobody asked for —
     * the local source does it only because there it costs a `readdir`.
     */
    private fun SmbFile.toEntry(): FileEntry {
        val directory = isDirectory
        val bare = name.trimEnd('/')
        return FileEntry(
            path = normalisedPath(),
            name = bare,
            isDirectory = directory,
            sizeBytes = if (directory) -1L else runCatching { length() }.getOrDefault(0L),
            modifiedEpochMs = runCatching { lastModified() }.getOrDefault(0L),
            // The Unix convention, which SMB does not use — but a share full of
            // files copied off a Linux box carries it anyway, and the DOS hidden
            // attribute is the other half of the same question.
            isHidden = bare.startsWith('.') || runCatching { isHidden }.getOrDefault(false),
            canWrite = runCatching { canWrite() }.getOrDefault(true),
            childCount = null,
        )
    }

    private companion object {
        const val TAG = "SMB"
        const val NO_SERVER = "No server is set up for that address"

        /**
         * Short enough that a sleeping NAS is reported rather than waited on.
         *
         * jcifs defaults to 35 seconds on the response timeout, which is a very
         * long time to hold an explorer that is never going to load.
         */
        const val CONNECT_TIMEOUT_MS = 8_000
        const val RESPONSE_TIMEOUT_MS = 15_000
        const val SOCKET_TIMEOUT_MS = 25_000
    }
}

/** jcifs's exceptions in words a user can act on. */
private fun SmbException.readable(): String {
    val text = message.orEmpty()
    return when {
        text.contains("Access is denied", ignoreCase = true) ->
            "The server allowed the connection but not this folder"

        text.contains("cannot find", ignoreCase = true) ||
            text.contains("does not exist", ignoreCase = true) ->
            "That folder is not on the server any more"

        text.contains("Connection timed out", ignoreCase = true) ||
            text.contains("Failed to connect", ignoreCase = true) ->
            "The server did not answer — it may be asleep or off the network"

        text.isBlank() -> "The server could not be reached"
        else -> text
    }
}

/**
 * A path jcifs will treat as a directory.
 *
 * Its own function because getting it wrong is silent: without the slash,
 * `smb://tower/games` names a *file*, and enumerating it fails with an error
 * about the path not being a directory when it plainly is one.
 */
internal fun directoryUrl(path: String): String =
    if (path.endsWith('/')) path else "$path/"

/**
 * Where the configured shares come from.
 *
 * An interface rather than a settings repository injected straight into the
 * source, so the SMB code has no view of the settings tree and can be handed an
 * empty list in a test without a DataStore behind it.
 */
fun interface SmbServerDirectory {
    suspend fun servers(): List<SmbServer>
}
