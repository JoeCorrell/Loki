package com.thor.data.media

import com.thor.core.common.log.ThorLog
import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.MediaId
import com.thor.core.model.MediaType
import com.thor.core.model.TraktSettings
import com.thor.data.BuildConfig
import com.thor.data.network.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Trakt.
 *
 * A record of what the viewer has watched, kept by them rather than by this
 * launcher. Three things come of it: shelves that are the same on every device
 * they use, a watchlist they can add to from anywhere, and — the reason most
 * people connect one — playback reported as it happens, so finishing a film here
 * marks it finished everywhere.
 *
 * ## Signing in without typing
 *
 * The device-code flow, not a redirect. A handheld has no address bar and its
 * text entry is a pad-driven keyboard, so the ordinary OAuth dance — open a
 * browser, sign in, come back through a redirect URI — is somewhere between
 * awkward and impossible. Instead Loki asks Trakt for a short code, shows it, and
 * the viewer types it on whatever device they already have in their hand. Loki
 * polls until they do. Nothing sensitive is ever typed on the handheld.
 *
 * ## What this returns
 *
 * Identities, not artwork. Trakt stopped serving images years ago, and the
 * launcher already has a catalogue that answers by IMDb id — the same id Trakt
 * speaks — so this hands back [MediaId]s and [MediaRepository] hydrates them
 * through the catalogue that draws every other shelf. One source of artwork, and
 * a Trakt row that looks like every row beside it.
 */
@Singleton
class TraktClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) {

    /**
     * Serialises token refreshes.
     *
     * A scrobble and a shelf load can want a valid token at the same moment, and
     * two concurrent refreshes both spend the same refresh token — Trakt
     * invalidates it on use, so the slower one comes back with a token that is
     * already dead and writes it over the good one. That is a sign-in lost for
     * no reason the user could ever explain.
     */
    private val tokenLock = Mutex()

    /** Whether this build carries an application registration at all. */
    val isAvailable: Boolean
        get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank() &&
            BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()

    // ---- Signing in --------------------------------------------------------

    /**
     * Asks Trakt for a code the viewer can type somewhere else.
     *
     * The reply carries how long it is good for and how often Loki may ask
     * whether it has been used; both are honoured rather than assumed, because
     * Trakt answers 429 to a client that polls faster than it was told to.
     */
    suspend fun requestDeviceCode(): TraktDeviceCode? {
        if (!isAvailable) return null

        val body = json.encodeToString(
            DeviceCodeRequest.serializer(),
            DeviceCodeRequest(clientId = BuildConfig.TRAKT_CLIENT_ID),
        )

        return runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url("$API/oauth/device/code")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .headers()
                    .build(),
            ).await()

            response.use {
                if (!it.isSuccessful) return null
                val payload = json.decodeFromString(
                    DeviceCodeResponse.serializer(),
                    it.body?.string().orEmpty(),
                )
                TraktDeviceCode(
                    deviceCode = payload.deviceCode,
                    userCode = payload.userCode,
                    verificationUrl = payload.verificationUrl,
                    expiresInSeconds = payload.expiresIn,
                    intervalSeconds = payload.interval.coerceAtLeast(MIN_POLL_SECONDS),
                )
            }
        }.onFailure { ThorLog.w(TAG, "Could not start sign-in: ${it.message}") }.getOrNull()
    }

    /**
     * Asks once whether the code has been entered yet.
     *
     * One attempt rather than a loop, because the waiting belongs to the caller:
     * it is what puts the code on screen, and it is what has to stop when the
     * user walks away from the page. The status codes are Trakt's own and are
     * translated here rather than leaked upward.
     */
    suspend fun pollForToken(deviceCode: String): TraktPollResult {
        if (!isAvailable) return TraktPollResult.Failed("Trakt is not available in this build")

        val body = json.encodeToString(
            DeviceTokenRequest.serializer(),
            DeviceTokenRequest(
                code = deviceCode,
                clientId = BuildConfig.TRAKT_CLIENT_ID,
                clientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
            ),
        )

        return runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url("$API/oauth/device/token")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .headers()
                    .build(),
            ).await()

            response.use {
                when (it.code) {
                    200 -> {
                        val token = json.decodeFromString(
                            TokenResponse.serializer(),
                            it.body?.string().orEmpty(),
                        )
                        store(token)
                        TraktPollResult.Connected(username = fetchUsername().orEmpty())
                    }

                    // Not yet — the viewer has the page open and has not finished.
                    400 -> TraktPollResult.Pending
                    404 -> TraktPollResult.Failed("That code is not valid any more")
                    409 -> TraktPollResult.Failed("That code has already been used")
                    410 -> TraktPollResult.Expired
                    418 -> TraktPollResult.Failed("Sign-in was refused on trakt.tv")
                    // Polling faster than Trakt allows. Not an error to report —
                    // the caller simply waits longer.
                    429 -> TraktPollResult.Pending
                    else -> TraktPollResult.Failed("Trakt answered ${it.code}")
                }
            }
        }.getOrElse { error ->
            ThorLog.w(TAG, "Sign-in poll failed: ${error.message}")
            TraktPollResult.Pending
        }
    }

    /** Forgets the account on this device. Nothing on Trakt is changed. */
    suspend fun disconnect() {
        settings.updateMedia { it.copy(trakt = TraktSettings()) }
    }

    /** The connected account, for the settings page. */
    suspend fun status(): TraktStatus {
        if (!isAvailable) return TraktStatus.Unavailable
        val trakt = settings.current().media.trakt
        if (!trakt.isConnected) return TraktStatus.NotConnected

        val name = fetchUsername()
        return if (name == null) TraktStatus.Expired else TraktStatus.Connected(name)
    }

    // ---- Shelves -----------------------------------------------------------

    /**
     * What the viewer has put aside to watch, newest first.
     *
     * Ids only; see the class note on why there is no artwork here.
     */
    suspend fun watchlist(type: MediaType): List<MediaId> {
        val path = if (type == MediaType.MOVIE) "movies" else "shows"
        val entries = get("/sync/watchlist/$path", ListEntry.serializer()) ?: return emptyList()
        return entries.mapNotNull { it.mediaId(type) }
    }

    /**
     * What the viewer is part-way through, from Trakt rather than from this device.
     *
     * The shelf that makes an account worth connecting: it holds the episode
     * started on a television last night, which this launcher has never seen.
     */
    suspend fun inProgress(type: MediaType): List<TraktResume> {
        val path = if (type == MediaType.MOVIE) "movies" else "episodes"
        val entries = get("/sync/playback/$path", PlaybackEntry.serializer()) ?: return emptyList()

        return entries.mapNotNull { entry ->
            val id = entry.mediaId(type) ?: return@mapNotNull null
            TraktResume(
                id = id,
                seasonNumber = entry.episode?.season,
                episodeNumber = entry.episode?.number,
                progressPercent = entry.progress,
            )
        }
    }

    suspend fun addToWatchlist(id: MediaId): Boolean = watchlistCall("/sync/watchlist", id)

    suspend fun removeFromWatchlist(id: MediaId): Boolean =
        watchlistCall("/sync/watchlist/remove", id)

    private suspend fun watchlistCall(path: String, id: MediaId): Boolean {
        val body = json.encodeToString(SyncRequest.serializer(), SyncRequest.of(id))
        return post(path, body) != null
    }

    // ---- Scrobbling --------------------------------------------------------

    /**
     * Reports the state of a play.
     *
     * Three calls over a film, not one per progress tick: Trakt's scrobble
     * endpoints are a state machine — start, pause, stop — and it rate-limits a
     * client that treats them as a progress feed. Stopping past
     * [WATCHED_THRESHOLD] percent is what makes Trakt count the title as watched,
     * which is why the position at the moment the player closes matters more than
     * everything reported before it.
     *
     * Silent on failure, deliberately. A scrobble that does not land is a missing
     * row on a website; interrupting somebody's film to tell them so would be a
     * far worse outcome than the thing it is reporting.
     */
    suspend fun scrobble(
        action: TraktScrobble,
        id: MediaId,
        seasonNumber: Int?,
        episodeNumber: Int?,
        progressPercent: Float,
    ) {
        val trakt = settings.current().media.trakt
        if (!trakt.isConnected || !trakt.scrobble) return

        val body = json.encodeToString(
            ScrobbleRequest.serializer(),
            ScrobbleRequest.of(id, seasonNumber, episodeNumber, progressPercent),
        )

        val path = when (action) {
            TraktScrobble.START -> "/scrobble/start"
            TraktScrobble.PAUSE -> "/scrobble/pause"
            TraktScrobble.STOP -> "/scrobble/stop"
        }

        if (post(path, body) == null) {
            ThorLog.w(TAG, "Scrobble ${action.name.lowercase()} for ${id.imdbId} did not land")
        }
    }

    // ---- Plumbing ----------------------------------------------------------

    /**
     * A valid access token, renewed first if it is close to expiring.
     *
     * Returns null when there is no account or the renewal failed, and every
     * caller treats that as "no Trakt" rather than as an error — a shelf simply
     * does not appear, which is the same thing that happens when it is empty.
     */
    private suspend fun accessToken(): String? = tokenLock.withLock {
        val trakt = settings.current().media.trakt
        if (!trakt.isConnected) return null

        val now = System.currentTimeMillis()
        if (!trakt.needsRefresh(now)) return trakt.accessToken

        val body = json.encodeToString(
            RefreshRequest.serializer(),
            RefreshRequest(
                refreshToken = trakt.refreshToken,
                clientId = BuildConfig.TRAKT_CLIENT_ID,
                clientSecret = BuildConfig.TRAKT_CLIENT_SECRET,
            ),
        )

        return runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url("$API/oauth/token")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .headers()
                    .build(),
            ).await()

            response.use {
                if (!it.isSuccessful) {
                    /*
                     * The refresh token is spent or revoked, and no amount of
                     * retrying brings it back. Clearing it is what stops every
                     * later call retrying a sign-in that cannot succeed — and
                     * what makes the settings page say "signed out" rather than
                     * showing an account that silently does nothing.
                     */
                    ThorLog.w(TAG, "Trakt refused the refresh token (${it.code}); signing out")
                    settings.updateMedia { media -> media.copy(trakt = TraktSettings()) }
                    return null
                }

                val token = json.decodeFromString(
                    TokenResponse.serializer(),
                    it.body?.string().orEmpty(),
                )
                store(token)
                token.accessToken
            }
        }.onFailure { ThorLog.w(TAG, "Could not renew the Trakt token: ${it.message}") }
            .getOrNull()
    }

    private suspend fun store(token: TokenResponse) {
        settings.updateMedia { media ->
            media.copy(
                trakt = media.trakt.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken,
                    // Trakt reports a lifetime in seconds from when it was
                    // created, not an instant; the absolute time is what a
                    // later launch can actually compare against.
                    expiresAtEpochMs = System.currentTimeMillis() + token.expiresIn * 1000L,
                ),
            )
        }
    }

    private suspend fun fetchUsername(): String? {
        val payload = getRaw("/users/settings") ?: return null
        val name = runCatching {
            json.decodeFromString(UserSettings.serializer(), payload).user.username
        }.getOrNull()

        name?.let { username ->
            settings.updateMedia { it.copy(trakt = it.trakt.copy(username = username)) }
        }
        return name
    }

    /** A GET returning a list, or null when Trakt could not be reached or refused. */
    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T>? {
        val payload = getRaw(path) ?: return null
        return runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer), payload)
        }.onFailure { ThorLog.w(TAG, "Could not read $path: ${it.message}") }.getOrNull()
    }

    private suspend fun getRaw(path: String): String? {
        val token = accessToken() ?: return null
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("$API$path?extended=full&limit=$PAGE_LIMIT")
                    .get()
                    .headers(token)
                    .build(),
            ).await().use { if (it.isSuccessful) it.body?.string() else null }
        }.onFailure { ThorLog.w(TAG, "$path failed: ${it.message}") }.getOrNull()
    }

    private suspend fun post(path: String, body: String): String? {
        val token = accessToken() ?: return null
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url("$API$path")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .headers(token)
                    .build(),
            ).await().use { if (it.isSuccessful) it.body?.string().orEmpty() else null }
        }.onFailure { ThorLog.w(TAG, "$path failed: ${it.message}") }.getOrNull()
    }

    /**
     * The headers every Trakt call carries.
     *
     * The version and the application key are not optional — Trakt answers 403
     * without them, which reads exactly like a bad token and sends anyone
     * debugging it to the wrong place entirely.
     */
    private fun Request.Builder.headers(token: String? = null): Request.Builder = apply {
        header("Content-Type", "application/json")
        header("trakt-api-version", "2")
        header("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
        token?.let { header("Authorization", "Bearer $it") }
    }

    private companion object {
        const val TAG = "Trakt"
        const val API = "https://api.trakt.tv"
        val JSON_MEDIA = "application/json".toMediaType()

        /** Enough for a shelf; nobody scrolls a hundred deep into a watchlist. */
        const val PAGE_LIMIT = 60

        /** Trakt's own floor, honoured even if it asks for less. */
        const val MIN_POLL_SECONDS = 5
    }
}

// ---- What the caller sees ---------------------------------------------------

/** A code to type on another device, and how long it is good for. */
data class TraktDeviceCode(
    val deviceCode: String,
    /** The short code the viewer reads off the screen — "A1B2C3D4". */
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    /** How often Trakt permits asking whether it has been entered. */
    val intervalSeconds: Int,
)

sealed interface TraktPollResult {
    /** The code has not been entered yet. Keep waiting. */
    data object Pending : TraktPollResult

    data class Connected(val username: String) : TraktPollResult

    /** The code timed out; a new one has to be asked for. */
    data object Expired : TraktPollResult

    data class Failed(val reason: String) : TraktPollResult
}

sealed interface TraktStatus {
    /** No application registration compiled in; see `data/build.gradle.kts`. */
    data object Unavailable : TraktStatus
    data object NotConnected : TraktStatus
    data class Connected(val username: String) : TraktStatus

    /** Tokens are stored but Trakt will not accept them. */
    data object Expired : TraktStatus
}

enum class TraktScrobble { START, PAUSE, STOP }

/** Somewhere the viewer got to, as Trakt has it. */
data class TraktResume(
    val id: MediaId,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** 0..100, which is the scale Trakt reports and accepts. */
    val progressPercent: Float,
)

// ---- Wire format ------------------------------------------------------------

@Serializable
private data class DeviceCodeRequest(@SerialName("client_id") val clientId: String)

@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
    val interval: Int,
)

@Serializable
private data class DeviceTokenRequest(
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
private data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
    // Required by Trakt even for the device flow, and it must match the one the
    // application was registered with.
    @SerialName("redirect_uri") val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0L,
)

@Serializable
private data class UserSettings(val user: TraktUser)

@Serializable
private data class TraktUser(val username: String = "")

@Serializable
private data class TraktIds(val imdb: String? = null, val tmdb: Int? = null)

@Serializable
private data class TraktTitle(val ids: TraktIds = TraktIds())

@Serializable
private data class TraktEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val ids: TraktIds = TraktIds(),
)

/** A watchlist row: one of `movie` or `show` is present, never both. */
@Serializable
private data class ListEntry(
    val movie: TraktTitle? = null,
    val show: TraktTitle? = null,
) {
    fun mediaId(type: MediaType): MediaId? {
        val ids = (movie ?: show)?.ids ?: return null
        val imdb = ids.imdb?.takeIf(String::isNotBlank) ?: return null
        return MediaId(type = type, imdbId = imdb, tmdbId = ids.tmdb)
    }
}

/**
 * A resume point.
 *
 * For an episode the useful identity is the *show's* IMDb id plus the season and
 * number — that is what every source in this launcher is keyed by, and what the
 * catalogue answers to. The episode's own id is deliberately ignored.
 */
@Serializable
private data class PlaybackEntry(
    val progress: Float = 0f,
    val movie: TraktTitle? = null,
    val show: TraktTitle? = null,
    val episode: TraktEpisode? = null,
) {
    fun mediaId(type: MediaType): MediaId? {
        val ids = (movie ?: show)?.ids ?: return null
        val imdb = ids.imdb?.takeIf(String::isNotBlank) ?: return null
        return MediaId(type = type, imdbId = imdb, tmdbId = ids.tmdb)
    }
}

@Serializable
private data class SyncMovie(val ids: SyncIds)

@Serializable
private data class SyncIds(val imdb: String)

@Serializable
private data class SyncRequest(
    val movies: List<SyncMovie> = emptyList(),
    val shows: List<SyncMovie> = emptyList(),
) {
    companion object {
        fun of(id: MediaId): SyncRequest {
            val entry = listOf(SyncMovie(SyncIds(id.imdbId)))
            return if (id.type == MediaType.MOVIE) {
                SyncRequest(movies = entry)
            } else {
                SyncRequest(shows = entry)
            }
        }
    }
}

@Serializable
private data class ScrobbleEpisode(val season: Int, val number: Int)

@Serializable
private data class ScrobbleRequest(
    val movie: SyncMovie? = null,
    val show: SyncMovie? = null,
    val episode: ScrobbleEpisode? = null,
    val progress: Float,
) {
    companion object {
        fun of(
            id: MediaId,
            seasonNumber: Int?,
            episodeNumber: Int?,
            progressPercent: Float,
        ): ScrobbleRequest {
            val entry = SyncMovie(SyncIds(id.imdbId))
            val progress = progressPercent.coerceIn(0f, 100f)

            // An episode is identified by its show and its numbers; a film by
            // itself. Sending a show with no episode would be a scrobble Trakt
            // cannot place.
            return if (id.type == MediaType.SERIES && seasonNumber != null && episodeNumber != null) {
                ScrobbleRequest(
                    show = entry,
                    episode = ScrobbleEpisode(seasonNumber, episodeNumber),
                    progress = progress,
                )
            } else {
                ScrobbleRequest(movie = entry, progress = progress)
            }
        }
    }
}
