package com.thor.data.media

import com.thor.core.common.log.ThorLog
import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.DebridService
import com.thor.data.network.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AllDebrid.
 *
 * The third service that answers the same three questions as the other two, and
 * it differs from them in one way worth stating at the top: **it will not say
 * what it has cached.**
 *
 * AllDebrid withdrew its instant-availability endpoint, and nothing replaced it —
 * their answer is that you upload the magnet and find out. So [cachedHashes]
 * returns null, which is the interface's way of saying "would not answer" rather
 * than "holds none of them". That distinction is load-bearing here: `cachedOnly`
 * is on by default and drops everything marked not-cached, so returning an empty
 * answer would empty the source list on every search. Null leaves every source
 * listed with its cache status unknown, which is the truth.
 *
 * In practice this costs less than it sounds. A magnet AllDebrid already holds
 * becomes ready the moment it is uploaded — that is what being cached *is* — so
 * a cached source still starts instantly. What is lost is knowing which one that
 * will be before pressing it.
 */
@Singleton
class AllDebridClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : DebridClient {

    override val service: DebridService = DebridService.ALL_DEBRID

    override suspend fun isConfigured(): Boolean = key() != null

    private suspend fun key(): String? =
        settings.media.first().allDebridApiKey.takeIf(String::isNotBlank)

    override suspend fun checkConnection(): DebridStatus {
        val key = key() ?: return DebridStatus.NotConfigured

        return try {
            get("/user", key).use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) return DebridStatus.Error("HTTP ${response.code}")

                val reply = json.decodeFromString<AdReply<AdUserData>>(raw)

                /*
                 * AllDebrid answers a bad key with HTTP 200 and an error body.
                 *
                 * The status line says nothing at all here — every reply is a
                 * 200 unless the service itself is down — so the envelope is the
                 * only place a rejected key is reported, and reading only the
                 * code would report a wrong key as a working account that
                 * mysteriously resolves nothing.
                 */
                val error = reply.error
                if (error != null) {
                    return if (error.code.orEmpty().startsWith("AUTH_")) {
                        DebridStatus.InvalidToken
                    } else {
                        DebridStatus.Error(error.message ?: "AllDebrid refused the request")
                    }
                }

                val user = reply.data?.user ?: return DebridStatus.InvalidToken
                DebridStatus.Connected(
                    username = user.username.orEmpty().ifBlank { user.email.orEmpty() },
                    daysRemaining = daysUntil(user.premiumUntil),
                )
            }
        } catch (e: IOException) {
            DebridStatus.Error(e.message ?: "Network error")
        } catch (e: IllegalArgumentException) {
            DebridStatus.Error("Unexpected reply from AllDebrid")
        }
    }

    /**
     * How long the account has left, from the epoch second AllDebrid gives.
     *
     * Seconds rather than the ISO timestamp TorBox uses, and zero for an account
     * with no subscription — which is reported as absent rather than as "expires
     * today", since the two look identical in a settings row and only one of them
     * is true.
     */
    private fun daysUntil(premiumUntilEpochSeconds: Long?): Int? {
        val expiry = premiumUntilEpochSeconds?.takeIf { it > 0L } ?: return null
        val remaining = expiry * 1000L - System.currentTimeMillis()
        return if (remaining <= 0L) 0 else TimeUnit.MILLISECONDS.toDays(remaining).toInt()
    }

    /**
     * Nothing, deliberately — see the note on the class.
     *
     * Null rather than an empty [CacheAvailability], and the difference is the
     * whole source list: empty means "checked, holds none", which `cachedOnly`
     * would act on by hiding every result.
     */
    override suspend fun cachedHashes(infoHashes: Collection<String>): CacheAvailability? = null

    override suspend fun resolve(
        magnetUri: String,
        fileIndex: Int?,
        instantFileIds: List<Int>,
        preferLargest: Boolean,
    ): ResolvedStream {
        val key = key() ?: return ResolvedStream.Failed("AllDebrid is not set up")

        return try {
            val magnetId = uploadMagnet(magnetUri, key)
                ?: return ResolvedStream.Failed("AllDebrid rejected the magnet")

            awaitFile(magnetId, key, fileIndex, preferLargest)
        } catch (e: IOException) {
            ResolvedStream.Failed(e.message ?: "Network error")
        } catch (e: IllegalArgumentException) {
            ResolvedStream.Failed("Unexpected reply from AllDebrid")
        }
    }

    /**
     * Hands the magnet over and takes back its id.
     *
     * A magnet already on the account is not an error: AllDebrid answers with the
     * existing entry, which is what a cached source is and the reason one is ready
     * on the first poll.
     */
    private suspend fun uploadMagnet(magnetUri: String, key: String): Long? {
        val body = FormBody.Builder().add("magnets[]", magnetUri).build()

        return post("/magnet/upload", key, body).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                ThorLog.w(TAG, "magnet/upload returned ${response.code}")
                return null
            }

            val reply = json.decodeFromString<AdReply<AdUploadData>>(raw)
            reply.error?.let {
                ThorLog.w(TAG, "magnet/upload refused: ${it.code} ${it.message}")
                return null
            }

            val magnet = reply.data?.magnets?.firstOrNull()
            // An entry can carry its own per-magnet error — a duplicate, or a
            // hash the service will not take — while the request as a whole
            // succeeded.
            magnet?.error?.let {
                ThorLog.w(TAG, "magnet refused: ${it.code} ${it.message}")
                return null
            }
            magnet?.id
        }
    }

    /**
     * Waits for the file list, chooses one, and unlocks it.
     *
     * Polled because the API offers nothing else. A cached magnet is ready on the
     * first pass, which is the common case; anything else is reported back as
     * progress rather than waited on indefinitely, so the viewer sees a figure
     * instead of an apparent hang.
     */
    private suspend fun awaitFile(
        magnetId: Long,
        key: String,
        fileIndex: Int?,
        preferLargest: Boolean,
    ): ResolvedStream {
        repeat(READY_POLL_ATTEMPTS) { attempt ->
            val status = magnetStatus(magnetId, key)
                ?: return ResolvedStream.Failed("AllDebrid lost track of this transfer")

            val links = status.links.orEmpty()
            if (status.statusCode == STATUS_READY && links.isNotEmpty()) {
                val chosen = chooseLink(links, fileIndex, preferLargest)
                    ?: return ResolvedStream.Failed("AllDebrid found no playable file")
                return unlock(chosen, key)
            }

            /*
             * A status past "ready" is a transfer that failed, expired or was
             * removed, and no amount of further polling changes it.
             */
            if (status.statusCode != null && status.statusCode > STATUS_READY) {
                return ResolvedStream.Failed(
                    status.status?.takeIf(String::isNotBlank) ?: "AllDebrid could not fetch this",
                )
            }

            if (attempt == READY_POLL_ATTEMPTS - 1) {
                val total = status.size ?: 0L
                val done = status.downloaded ?: 0L
                val fraction = if (total > 0L) (done.toFloat() / total) else 0f
                return ResolvedStream.Downloading(fraction.coerceIn(0f, 1f))
            }
            delay(READY_POLL_INTERVAL_MS)
        }

        return ResolvedStream.Downloading(0f)
    }

    private suspend fun magnetStatus(magnetId: Long, key: String): AdMagnetStatus? =
        get("/magnet/status?id=$magnetId", key).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                ThorLog.w(TAG, "magnet/status returned ${response.code}")
                return null
            }
            json.decodeFromString<AdReply<AdStatusData>>(raw).data?.magnets
        }

    /**
     * Which file to play.
     *
     * The index the source named where there is one and it points at a video —
     * an episode picked out of a season pack — and otherwise the largest video,
     * which is the film in a torrent that also carries samples, subtitles and a
     * readme.
     *
     * Indexed as the torrent indexes them, from zero, which is what the interface
     * promises the caller. AllDebrid's link list is in the torrent's own order,
     * so it needs no remapping the way another service might.
     */
    private fun chooseLink(
        links: List<AdLink>,
        fileIndex: Int?,
        preferLargest: Boolean,
    ): AdLink? {
        val playable = links.filter(::isVideo)
        if (playable.isEmpty()) return null

        fileIndex?.let { index ->
            links.getOrNull(index)?.takeIf(::isVideo)?.let { return it }
        }

        return if (preferLargest) {
            playable.maxByOrNull { it.size ?: 0L }
        } else {
            playable.firstOrNull()
        }
    }

    private fun isVideo(link: AdLink): Boolean {
        val name = link.filename.orEmpty()
        return VIDEO_EXTENSIONS.any { extension -> name.endsWith(extension, ignoreCase = true) }
    }

    /** Turns a locked link into the direct URL the player opens. */
    private suspend fun unlock(link: AdLink, key: String): ResolvedStream {
        val target = link.link?.takeIf(String::isNotBlank)
            ?: return ResolvedStream.Failed("AllDebrid returned no link")

        val body = FormBody.Builder().add("link", target).build()

        return post("/link/unlock", key, body).use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                return ResolvedStream.Failed("AllDebrid returned HTTP ${response.code}")
            }

            val reply = json.decodeFromString<AdReply<AdUnlockData>>(raw)
            reply.error?.let {
                return ResolvedStream.Failed(it.message ?: "AllDebrid would not unlock this")
            }

            val url = reply.data?.link?.takeIf(String::isNotBlank)
                ?: return ResolvedStream.Failed("AllDebrid returned no link")

            ResolvedStream.Ready(url = url, fileName = reply.data.filename ?: link.filename)
        }
    }

    // ---- HTTP --------------------------------------------------------------

    /**
     * Every call carries an agent, which AllDebrid requires rather than prefers.
     *
     * Without it the API answers with an error envelope on an HTTP 200, which is
     * the same shape as a bad key — so a missing agent presents as "your key was
     * rejected" and sends anyone debugging it to re-copy a key that was fine.
     */
    private fun url(path: String): String {
        // The path may already carry its own parameters; `agent` is always first
        // so what follows is appended with `&` either way.
        val separator = if (path.contains('?')) "&" else "?"
        return "$BASE_URL$path${separator}agent=$AGENT"
    }

    private suspend fun get(path: String, key: String) =
        client.newCall(
            Request.Builder()
                .url(url(path))
                .header("Authorization", "Bearer $key")
                .build(),
        ).await()

    private suspend fun post(path: String, key: String, body: FormBody) =
        client.newCall(
            Request.Builder()
                .url(url(path))
                .header("Authorization", "Bearer $key")
                .post(body)
                .build(),
        ).await()

    private companion object {
        const val TAG = "Media"
        const val BASE_URL = "https://api.alldebrid.com/v4"

        /** Identifies the application to AllDebrid; required on every call. */
        const val AGENT = "loki"

        /** AllDebrid's own code for a magnet whose files are available. */
        const val STATUS_READY = 4

        const val READY_POLL_ATTEMPTS = 10
        const val READY_POLL_INTERVAL_MS = 1_500L

        val VIDEO_EXTENSIONS = listOf(".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".webm")
    }
}

// ---- Wire format ------------------------------------------------------------

/**
 * The envelope every AllDebrid reply arrives in.
 *
 * `status` is "success" or "error", and the HTTP code is 200 either way — see
 * the note in `checkConnection`. Both payloads are nullable so a reply carrying
 * one cannot fail to parse for want of the other.
 */
@Serializable
private data class AdReply<T>(
    val status: String = "",
    val data: T? = null,
    val error: AdError? = null,
)

@Serializable
private data class AdError(val code: String? = null, val message: String? = null)

@Serializable
private data class AdUserData(val user: AdUser? = null)

@Serializable
private data class AdUser(
    val username: String? = null,
    val email: String? = null,
    val isPremium: Boolean = false,
    /** Epoch seconds, or absent on an account with no subscription. */
    val premiumUntil: Long? = null,
)

@Serializable
private data class AdUploadData(val magnets: List<AdUploadedMagnet> = emptyList())

@Serializable
private data class AdUploadedMagnet(
    val id: Long? = null,
    val hash: String? = null,
    val name: String? = null,
    val ready: Boolean = false,
    /** Set when this magnet in particular was refused, the request having succeeded. */
    val error: AdError? = null,
)

@Serializable
private data class AdStatusData(val magnets: AdMagnetStatus? = null)

@Serializable
private data class AdMagnetStatus(
    val id: Long? = null,
    val filename: String? = null,
    val size: Long? = null,
    val status: String? = null,
    @SerialName("statusCode") val statusCode: Int? = null,
    val downloaded: Long? = null,
    val links: List<AdLink> = emptyList(),
)

@Serializable
private data class AdLink(
    val link: String? = null,
    val filename: String? = null,
    val size: Long? = null,
)

@Serializable
private data class AdUnlockData(
    val link: String? = null,
    val filename: String? = null,
    val filesize: Long? = null,
)
