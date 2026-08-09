package com.thor.data.metadata

import android.content.Context
import com.thor.core.common.dispatchers.Dispatcher
import com.thor.core.common.dispatchers.ThorDispatcher
import com.thor.core.common.log.ThorLog
import com.thor.core.model.ArtworkSet
import com.thor.data.network.await
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Keeps scraped artwork on the device, for good.
 *
 * Without this, a scrape stores the *address* of an image and nothing else. Coil then caches the
 * bytes in `cacheDir`, which is bounded at 512 MB, evicts its oldest entries, and is the first
 * thing Android reclaims when storage runs short. For a provider whose images live on a public
 * server that is merely wasteful — the image is re-fetched and nobody notices. For artwork served
 * by a PC in the next room it is the difference between a library that works and a grid full of
 * blank cells whenever that PC is asleep.
 *
 * So the image is copied into `filesDir`, which belongs to the application and is never reclaimed
 * behind its back, and the stored URI becomes a `file://` — exactly what [ArtworkSet] always
 * documented its values could be.
 *
 * Files are named by a hash of their source URL rather than by the game, which means a re-scrape
 * of the same artwork costs nothing and two games sharing an image share one file. The cost is
 * that deletion cannot be per-game; see [sweep].
 */
@Singleton
class ArtworkStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    @Dispatcher(ThorDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    private val directory: File by lazy {
        File(context.filesDir, "artwork").apply { mkdirs() }
    }

    /**
     * Downloads everything in [set] that is still remote and returns it pointing at local files.
     *
     * A failure is not fatal and not even reported upward: the original URL is kept, so that
     * slot behaves exactly as it did before this class existed. Losing the artwork entirely
     * because the copy failed would be a worse outcome than the problem being solved.
     */
    suspend fun localise(set: ArtworkSet): ArtworkSet = withContext(ioDispatcher) {
        if (set.isEmpty) return@withContext set

        // Bounded, because a library scrape reaches this once per game and each game brings
        // half a dozen images. Unbounded, a single scrape would open hundreds of sockets.
        val gate = Semaphore(MAX_CONCURRENT_DOWNLOADS)

        coroutineScope {
            val boxArt = async { fetch(set.boxArt, gate) }
            val hero = async { fetch(set.hero, gate) }
            val logo = async { fetch(set.logo, gate) }
            val icon = async { fetch(set.icon, gate) }
            val screenshots = set.cappedScreenshots.map { async { fetch(it, gate) } }

            set.copy(
                boxArt = boxArt.await(),
                hero = hero.await(),
                logo = logo.await(),
                icon = icon.await(),
                screenshots = screenshots.awaitAll().filterNotNull(),
                // Video is deliberately left remote. A clip is tens of megabytes against an
                // image's hundreds of kilobytes, and it plays once after a dwell rather than
                // being drawn on every frame of a grid.
            )
        }
    }

    /**
     * Deletes stored artwork nothing refers to any more.
     *
     * Necessary because files are keyed by image rather than by game, so removing an entry
     * cannot simply delete its folder. Passing the complete set of URIs the library still uses
     * is the caller's job — a partial set here would delete artwork that is still on screen.
     *
     * @return how many bytes were reclaimed
     */
    suspend fun sweep(referenced: Set<String>): Long = withContext(ioDispatcher) {
        val keep = referenced.mapNotNullTo(mutableSetOf()) { uri ->
            uri.removePrefix(FILE_SCHEME).takeIf { it != uri }?.let(::File)?.name
        }

        var reclaimed = 0L
        directory.listFiles()?.forEach { file ->
            coroutineContext.ensureActive()
            if (file.name in keep) return@forEach
            val size = file.length()
            if (file.delete()) reclaimed += size
        }

        if (reclaimed > 0) ThorLog.d(TAG) { "Reclaimed ${reclaimed / 1024} KB of unreferenced artwork" }
        reclaimed
    }

    /** Total bytes currently held, for the settings screen to report. */
    suspend fun bytesUsed(): Long = withContext(ioDispatcher) {
        directory.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Resolves one URI to a local file, downloading it if this is the first time.
     *
     * Null and already-local values pass straight through — the second is what makes a re-scrape
     * cheap, since everything kept from last time is already a `file://`.
     */
    private suspend fun fetch(uri: String?, gate: Semaphore): String? {
        if (uri.isNullOrBlank()) return null
        if (uri.startsWith(FILE_SCHEME) || uri.startsWith("content://")) return uri
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) return uri

        val target = File(directory, fileNameFor(uri))
        if (target.exists() && target.length() > 0) return FILE_SCHEME + target.absolutePath

        return gate.withPermit {
            runCatching { download(uri, target) }
                .onFailure { ThorLog.w(TAG, "Could not store artwork from $uri", it) }
                .getOrNull()
                ?: uri
        }
    }

    private suspend fun download(uri: String, target: File): String? {
        val request = Request.Builder().url(uri).build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null

            /*
             * Written to a neighbouring file and renamed into place.
             *
             * A download interrupted by the app being killed would otherwise leave a truncated
             * file at the final name, and every later scrape would treat it as already stored —
             * a permanently half-drawn cover that nothing would ever repair.
             */
            val partial = File(target.absolutePath + ".part")
            partial.outputStream().use { out -> body.byteStream().copyTo(out) }

            if (partial.length() == 0L) {
                partial.delete()
                return null
            }
            if (!partial.renameTo(target)) {
                partial.delete()
                return null
            }
        }
        return FILE_SCHEME + target.absolutePath
    }

    /**
     * A stable name derived from the source URL.
     *
     * SHA-256 rather than the URL's own last path segment: LaunchBox names images by GUID and
     * would be fine, but a libretro URL ends in the game's title, and two platforms holding a
     * game of the same name would collide on it.
     */
    private fun fileNameFor(uri: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        val extension = uri.substringAfterLast('.', "").substringBefore('?')
            .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
            ?: "img"
        return "$hex.$extension"
    }

    private companion object {
        const val TAG = "ArtworkStore"
        const val FILE_SCHEME = "file://"

        /**
         * Concurrent image downloads across a whole scrape.
         *
         * Four rather than more: on a companion setup every one of these is a request to a
         * single PC, and saturating it makes the identification requests — which the scrape is
         * actually waiting on — queue behind a pile of image transfers.
         */
        const val MAX_CONCURRENT_DOWNLOADS = 4
    }
}
