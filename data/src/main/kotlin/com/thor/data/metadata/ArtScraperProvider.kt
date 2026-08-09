package com.thor.data.metadata

import com.thor.core.common.log.ThorLog
import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.ArtworkSet
import com.thor.core.model.GameMetadata
import com.thor.data.network.await
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ArtScraper — a companion running on the user's own PC.
 *
 * Every other provider here talks to somebody else's service and needs a credential to do it.
 * This one talks to a program the user is running themselves, on their own network, and needs
 * nothing but its address. That is the whole reason it exists: on a fresh install with no keys
 * entered anywhere, it is the only source that can put a cover on a grid cell.
 *
 * It is also the most accurate of them, and for a reason worth stating plainly. The others are
 * asked "is there a game called something like this?" and answer from a title. ArtScraper is
 * told the ROM's CRC32, looks it up in the No-Intro and Redump catalogues, and gets back the
 * canonical name of that exact dump — right game, right region, right revision. libretro's
 * thumbnail files are named byte-for-byte after those same catalogues, so fetching the artwork
 * is then a URL built from a known name rather than a search that might land anywhere.
 *
 * The desktop does all of it. The 300 MB LaunchBox database, the DAT indexes and the image
 * cache all stay where there is room for them, and the handheld sends three hashes it already
 * computed during the scan (see `RomHasher`) and receives a list of URLs.
 */
@Singleton
class ArtScraperProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : MetadataProvider {

    override val id: String = ID
    override val displayName: String = "ArtScraper (your PC)"

    /**
     * Configured means "we have somewhere to ask", and nothing more.
     *
     * Deliberately not a reachability test. This is called before every scrape, and a probe
     * here would put a network round-trip in front of each one; whether the PC is actually
     * awake is what [checkConnection] and the request itself answer.
     */
    override suspend fun isConfigured(): Boolean = baseUrl() != null

    private suspend fun baseUrl(): HttpUrl? {
        val host = settings.metadata.first().artScraperHost.trim()
        if (host.isBlank()) return null
        return normalise(host)
    }

    /**
     * Accepts what a person would actually type.
     *
     * "192.168.1.20", "192.168.1.20:8756" and "http://desktop.local:8756/" are all the same
     * intent, and rejecting two of the three because they lack a scheme or a port would be a
     * settings field that looks broken. The default port is the one `artscraper serve` uses.
     */
    private fun normalise(host: String): HttpUrl? {
        val withScheme = if (host.startsWith("http://") || host.startsWith("https://")) {
            host
        } else {
            "http://$host"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        return if (parsed.port == 80 && !withScheme.contains(":$DEFAULT_PORT") && !host.contains(':')) {
            parsed.newBuilder().port(DEFAULT_PORT).build()
        } else {
            parsed
        }
    }

    override suspend fun checkConnection(): ProviderStatus {
        val base = baseUrl() ?: return ProviderStatus.NotConfigured
        val url = base.newBuilder().addPathSegments("api/health").build()

        return try {
            client.newCall(Request.Builder().url(url).build()).await().use { response ->
                if (!response.isSuccessful) return ProviderStatus.Error("HTTP ${response.code}")

                val body = response.body?.string().orEmpty()
                val health = runCatching { json.decodeFromString<Health>(body) }.getOrNull()
                    ?: return ProviderStatus.Error("Something is answering, but it is not ArtScraper")

                when {
                    health.service != "artscraper" ->
                        ProviderStatus.Error("Something is answering, but it is not ArtScraper")

                    health.protocol > PROTOCOL ->
                        ProviderStatus.Error("That ArtScraper is newer than this launcher understands")

                    // Reachable but half-equipped. Reported rather than swallowed, because the
                    // symptom — covers appear, descriptions never do — reads as a broken
                    // scraper instead of a database the user has not imported yet.
                    !health.launchBox ->
                        ProviderStatus.Error("Connected to ${health.host}, but run 'artscraper import' for metadata and fanart")

                    else -> ProviderStatus.Connected
                }
            }
        } catch (e: IOException) {
            ProviderStatus.Unreachable(e.message ?: "Could not reach that address")
        }
    }

    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        val base = baseUrl() ?: return emptyList()

        val url = base.newBuilder()
            .addPathSegments("api/match")
            // Loki's own platform id. The server resolves it against its own table of names and
            // folder aliases, which already knows that "lynx" is the Atari Lynx — so a platform
            // the user invented themselves resolves too, where its name is recognisable.
            .addQueryParameter("platform", query.platformId)
            .addQueryParameter("name", query.fileName)
            .addQueryParameter("size", query.fileSizeBytes.toString())
            .addQueryParameter("images", ArtworkSet.MAX_SCREENSHOTS.toString())
            .apply {
                // The fingerprints are the entire point. Everything above this is a fallback
                // for a file too large to have been hashed.
                query.crc32?.let { addQueryParameter("crc32", it) }
                query.md5?.let { addQueryParameter("md5", it) }
                query.sha1?.let { addQueryParameter("sha1", it) }
                query.region?.let { addQueryParameter("region", it) }
            }
            .build()

        return try {
            val body = get(url) ?: return emptyList()
            val result = json.decodeFromString<MatchResult>(body)
            listOfNotNull(result.toCandidate(query))
        } catch (e: IOException) {
            ThorLog.w(TAG, "Could not reach ArtScraper for '${query.fileName}'", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            ThorLog.w(TAG, "Malformed ArtScraper response for '${query.fileName}'", e)
            emptyList()
        } catch (e: IllegalStateException) {
            ThorLog.w(TAG, "Unexpected ArtScraper response for '${query.fileName}'", e)
            emptyList()
        }
    }

    private suspend fun get(url: HttpUrl): String? =
        client.newCall(Request.Builder().url(url).build()).await().use { response ->
            when {
                response.isSuccessful -> response.body?.string()
                // The server refuses a platform it cannot place. Routine, not an error.
                response.code == 400 -> null
                else -> {
                    ThorLog.w(TAG, "ArtScraper returned ${response.code}")
                    null
                }
            }
        }

    /**
     * Turns one answer into a candidate the aggregator can rank.
     *
     * A result the server marked `needsReview` is a similarity score, not an identification, and
     * is reported below [MetadataAggregator.MIN_CONFIDENCE] so it cannot contribute a single
     * field on its own. It is still returned rather than dropped: the manual picker shows the
     * unfiltered set, and a person looking at the box can recognise what a score could not.
     */
    private fun MatchResult.toCandidate(query: MetadataQuery): MetadataCandidate? {
        if (!matched && !needsReview) return null

        val artwork = art.toArtworkSet()
        if (artwork.isEmpty && metadata == null) return null

        return MetadataCandidate(
            providerId = ID,
            remoteId = name ?: title.orEmpty(),
            matchedTitle = metadata?.title ?: title ?: query.title,
            confidence = when {
                // A hash hit is not a guess and there is nothing to be uncertain about.
                !needsReview && confidence == "Certain" -> 1f
                !needsReview -> 0.9f
                // Deliberately under the aggregator's floor. See the doc comment.
                else -> 0.3f
            },
            metadata = metadata?.let { meta ->
                GameMetadata(
                    description = meta.overview,
                    genres = meta.genres,
                    developer = meta.developer,
                    publisher = meta.publisher,
                    releaseYear = meta.year,
                    // LaunchBox rates out of five; every other provider here is out of a hundred.
                    rating = meta.rating?.let { (it * 20).toInt().coerceIn(0, 100) },
                    players = meta.players?.takeIf { it > 0 }?.toString(),
                    region = region,
                    providerSources = buildMap {
                        if (!meta.overview.isNullOrBlank()) put(GameMetadata.FIELD_DESCRIPTION, ID)
                        if (meta.genres.isNotEmpty()) put(GameMetadata.FIELD_GENRES, ID)
                        if (!meta.developer.isNullOrBlank()) put(GameMetadata.FIELD_DEVELOPER, ID)
                        if (!meta.publisher.isNullOrBlank()) put(GameMetadata.FIELD_PUBLISHER, ID)
                        if (meta.year != null) put(GameMetadata.FIELD_RELEASE_DATE, ID)
                        if (meta.rating != null) put(GameMetadata.FIELD_RATING, ID)
                    },
                )
            } ?: GameMetadata.EMPTY,
            artwork = artwork,
        )
    }

    /**
     * Maps ArtScraper's media kinds onto Loki's artwork slots.
     *
     * The server has already ordered each list by its own ranking — region first, then source,
     * then image type — so taking the front of a list here yields the same image a desktop
     * scrape would have written to disk. Nothing is re-sorted on the device.
     */
    private fun Map<String, List<ArtImage>>.toArtworkSet(): ArtworkSet {
        fun first(vararg kinds: String): String? =
            kinds.firstNotNullOfOrNull { this[it]?.firstOrNull()?.url }

        fun all(vararg kinds: String): List<String> =
            kinds.flatMap { this[it].orEmpty() }.map(ArtImage::url)

        return ArtworkSet(
            boxArt = first("boxFront", "box3D"),
            /*
             * Fanart, and only fanart.
             *
             * This is the image behind the whole top screen. Fanart is drawn key art at that
             * shape and is the one thing here designed to sit behind a title; a gameplay snap
             * is a 4:3 frame from a 1990s console, and stretched across a background it looks
             * like a mistake rather than a choice. Where a game has no fanart the slot stays
             * empty and `ArtworkSet.backgroundImage` falls back on its own.
             */
            hero = first("fanart"),
            logo = first("clearLogo"),
            /*
             * Genuinely square art, or nothing.
             *
             * The icon feeds a 1:1 grid cell, and the server only files an image under this
             * kind when its dimensions really are square. A cartridge photograph used to stand
             * in here and looked like a photograph of a cartridge; falling through to the cover
             * via `ArtworkSet.cellImage` is the better answer, so leaving this null is
             * deliberate rather than a gap.
             */
            icon = first("icon"),
            // Gameplay first, title screens after: a title screen is the least interesting of
            // the two and should fill the strip rather than lead it. Fanart is excluded — it is
            // the background, and repeating it here would show the same image twice.
            screenshots = (all("screenshot") + all("titleScreen"))
                .distinct()
                .take(ArtworkSet.MAX_SCREENSHOTS),
        )
    }

    // ------------------------------------------------------------------ DTOs

    @Serializable
    private data class Health(
        val service: String = "",
        val protocol: Int = 0,
        val host: String = "",
        val launchBox: Boolean = false,
    )

    @Serializable
    private data class MatchResult(
        val matched: Boolean = false,
        val needsReview: Boolean = false,
        val name: String? = null,
        val title: String? = null,
        val region: String? = null,
        val confidence: String = "None",
        val explanation: String = "",
        val metadata: RemoteMetadata? = null,
        val art: Map<String, List<ArtImage>> = emptyMap(),
    )

    @Serializable
    private data class RemoteMetadata(
        val title: String? = null,
        val year: Int? = null,
        val developer: String? = null,
        val publisher: String? = null,
        val genres: List<String> = emptyList(),
        val overview: String? = null,
        val rating: Double? = null,
        val players: Int? = null,
    )

    @Serializable
    private data class ArtImage(val url: String)

    companion object {
        const val ID = "artscraper"

        /** What `artscraper serve` listens on unless told otherwise. */
        const val DEFAULT_PORT = 8756

        /**
         * The wire format this client understands.
         *
         * A server reporting a higher number has changed the shape of something, so it is
         * refused with an explanation rather than parsed into a half-empty record.
         */
        const val PROTOCOL = 1

        private const val TAG = "ArtScraper"
    }
}
