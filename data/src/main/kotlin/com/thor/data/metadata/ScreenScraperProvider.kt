package com.thor.data.metadata

import com.thor.core.common.log.ThorLog
import com.thor.data.BuildConfig
import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.ArtworkSet
import com.thor.core.model.GameMetadata
import com.thor.data.network.await
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ScreenScraper — the deepest catalogue for retro systems.
 *
 * Matched on the *file* first, and only then on the title. `jeuInfos.php` is
 * given the ROM's hashes, name and size, and ScreenScraper resolves that against
 * its own dump database — which for a library named to No-Intro and Redump
 * conventions is exact, and needs no fuzzy confidence at all: a hit is the right
 * game, reported at 1.0.
 *
 * A file it has never seen falls through to [searchByName]. That path is a
 * guess and is scored like one, because it was the absence of it that made a
 * library of ordinarily-named files come back empty from a database that had
 * every one of those games.
 */
@Singleton
class ScreenScraperProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val settings: SettingsRepository,
) : MetadataProvider {

    override val id: String = ID
    override val displayName: String = "ScreenScraper"

    /**
     * Either credential will do: the user's account, or the build's developer
     * key, or both.
     *
     * This used to demand the developer key and nothing else, which was wrong in
     * the way that matters most — a user who had entered their ScreenScraper
     * username and password got a provider that refused to make a single
     * request, and settings that told them their account could not help. An
     * account is a login to the same service; if it authorises requests, the
     * launcher has no business insisting on a second credential it does not have.
     *
     * Both are sent when both exist. The developer key raises what the
     * application is allowed, the account raises what the user is allowed, and
     * they are not alternatives so much as two halves of the same quota.
     */
    override suspend fun isConfigured(): Boolean = hasDeveloperKey || hasUserAccount()

    private suspend fun hasUserAccount(): Boolean {
        val config = settings.metadata.first()
        return config.screenScraperUser.isNotBlank() && config.screenScraperPassword.isNotBlank()
    }

    override suspend fun checkConnection(): ProviderStatus {
        if (!isConfigured()) {
            return ProviderStatus.Error(
                "Sign in with a ScreenScraper account, or build with a developer key",
            )
        }
        val config = settings.metadata.first()

        // `ssinfraInfos` is the cheapest endpoint that still validates the key.
        val url = "$BASE_URL/ssinfraInfos.php".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("output", "json")
            ?.addQueryParameter("softname", SOFT_NAME)
            ?.apply {
                // Only what exists. An empty `devid` is not the same as no
                // `devid` — it is a credential presented and blank, which is a
                // rejection rather than an anonymous request.
                addCredentials(config)
            }
            ?.build()
            ?: return ProviderStatus.Error("Malformed URL")

        return try {
            client.newCall(Request.Builder().url(url).build()).await().use { response ->
                when {
                    response.isSuccessful -> ProviderStatus.Connected
                    response.code == 401 || response.code == 403 ->
                        ProviderStatus.InvalidCredentials

                    response.code == 429 -> ProviderStatus.Error("Daily quota reached")
                    else -> ProviderStatus.Error("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            ProviderStatus.Unreachable(e.message ?: "No connection")
        }
    }

    override suspend fun search(query: MetadataQuery): List<MetadataCandidate> {
        if (!isConfigured()) return emptyList()
        val config = settings.metadata.first()

        val systemId = query.providerPlatformIds["screenscraper"] ?: run {
            ThorLog.d(TAG) { "No ScreenScraper system id for ${query.platformId}" }
            return emptyList()
        }

        val url = "$BASE_URL/jeuInfos.php".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("softname", SOFT_NAME)
            ?.addQueryParameter("output", "json")
            ?.addQueryParameter("systemeid", systemId)
            ?.addQueryParameter("romnom", query.fileName)
            // Size narrows an ambiguous filename to a specific dump.
            ?.addQueryParameter("romtaille", query.fileSizeBytes.toString())
            /*
             * The fingerprints, which outrank everything above them.
             *
             * ScreenScraper indexes dumps by hash, so given one it answers about
             * the exact file rather than about the closest name — which is the
             * whole difference between a scraper that gets regional variants and
             * revisions right and one that guesses. Sent alongside the name
             * rather than instead of it: a file the database has never seen falls
             * back to the name match, and an unknown hash is not an error.
             */
            ?.apply {
                query.crc32?.let { addQueryParameter("crc", it) }
                query.md5?.let { addQueryParameter("md5", it) }
                query.sha1?.let { addQueryParameter("sha1", it) }
            }
            ?.apply { addCredentials(config) }
            ?.build()
            ?: return emptyList()

        val byFile = try {
            val body = get(url.toString())
            body?.let { json.decodeFromString<SsEnvelope>(it).response?.jeu }
        } catch (e: IOException) {
            ThorLog.w(TAG, "Request failed for '${query.fileName}'", e)
            null
        } catch (e: IllegalStateException) {
            // A quota rejection comes back as plain text rather than JSON, so a
            // parse failure here is expected rather than exceptional.
            ThorLog.w(TAG, "Unexpected response for '${query.fileName}'", e)
            null
        } catch (e: IllegalArgumentException) {
            ThorLog.w(TAG, "Malformed response for '${query.fileName}'", e)
            null
        }

        if (byFile != null) return listOf(byFile.toCandidate(query))

        return searchByName(query, systemId, config)
    }

    /**
     * The fallback for a file ScreenScraper's dump database has never seen.
     *
     * `jeuInfos.php` identifies a *file*: given a hash it is exact, and given
     * neither a known hash nor a No-Intro filename it returns nothing at all.
     * That is the right behaviour for what it is and it was the whole of this
     * provider, which meant a library named the way people actually name
     * things — `Super Mario World.smc` rather than `Super Mario World (USA).sfc`
     * — came back empty from a database that has the game, the cover and the
     * screenshots sitting right there. Verified against the live API: the first
     * name misses, the second hits, and the search endpoint finds it from either.
     *
     * So when the file is not recognised, the *title* is asked about instead.
     * Every candidate is scored on how well its name matches rather than being
     * trusted, because this endpoint answers a question about a string: searching
     * for "Mario" returns thirty games and all of them are real answers to what
     * was asked and not to what was meant.
     */
    private suspend fun searchByName(
        query: MetadataQuery,
        systemId: String,
        config: com.thor.core.model.MetadataSettings,
    ): List<MetadataCandidate> {
        val term = query.title.trim().takeIf { it.length >= MIN_SEARCH_TERM } ?: return emptyList()

        val url = "$BASE_URL/jeuRecherche.php".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("softname", SOFT_NAME)
            ?.addQueryParameter("output", "json")
            ?.addQueryParameter("systemeid", systemId)
            ?.addQueryParameter("recherche", term)
            ?.apply { addCredentials(config) }
            ?.build()
            ?: return emptyList()

        val games = try {
            get(url.toString())?.let { json.decodeFromString<SsEnvelope>(it).response?.jeux }
        } catch (e: IOException) {
            ThorLog.w(TAG, "Name search failed for '$term'", e)
            null
        } catch (e: IllegalStateException) {
            ThorLog.w(TAG, "Unexpected search response for '$term'", e)
            null
        } catch (e: IllegalArgumentException) {
            ThorLog.w(TAG, "Malformed search response for '$term'", e)
            null
        } ?: return emptyList()

        /*
         * Only the candidates worth offering, best first.
         *
         * Capped rather than returned whole: thirty results per game, each
         * carrying its full media list, is thirty times the parsing and thirty
         * rows in the "which game is this?" dialog for one answer.
         */
        return games
            .map { it.toCandidate(query, confidence = TitleMatcher.confidence(term, it.names.pick(query.region).orEmpty())) }
            .filter { it.confidence >= NAME_MATCH_FLOOR }
            .sortedByDescending { it.confidence }
            .take(MAX_SEARCH_RESULTS)
    }

    /**
     * Adds whichever credentials this launcher actually has.
     *
     * Each is added only when it is there. A blank `devid` is not equivalent to
     * omitting it — it is a credential offered and empty, which a service reads
     * as a bad login rather than as an anonymous request.
     */
    private fun okhttp3.HttpUrl.Builder.addCredentials(config: com.thor.core.model.MetadataSettings) {
        DEV_ID.takeIf(String::isNotBlank)?.let { addQueryParameter("devid", it) }
        DEV_PASSWORD.takeIf(String::isNotBlank)?.let { addQueryParameter("devpassword", it) }
        config.screenScraperUser.takeIf(String::isNotBlank)
            ?.let { addQueryParameter("ssid", it) }
        config.screenScraperPassword.takeIf(String::isNotBlank)
            ?.let { addQueryParameter("sspassword", it) }
    }

    private suspend fun get(url: String): String? {
        val request = Request.Builder().url(url).build()
        return client.newCall(request).await().use { response ->
            when {
                response.isSuccessful -> response.body?.string()
                // 404 is ScreenScraper's "no such game", which is routine.
                response.code == 404 -> null
                response.code == 401 || response.code == 403 -> {
                    ThorLog.w(TAG, "ScreenScraper rejected the credentials (${response.code})")
                    null
                }
                response.code == 429 -> {
                    ThorLog.w(TAG, "ScreenScraper quota exhausted for today")
                    null
                }
                else -> {
                    ThorLog.w(TAG, "ScreenScraper returned ${response.code}")
                    null
                }
            }
        }
    }

    private fun SsGame.toCandidate(
        query: MetadataQuery,
        /**
         * How sure we are this is the right game.
         *
         * 1.0 for a hit from `jeuInfos.php`, which matched the file itself. Lower
         * for one dredged up by name from `jeuRecherche.php`, where the database
         * has answered a question about a *string* and the aggregator's
         * confidence floor is the thing standing between a loose match and
         * somebody else's cover.
         */
        confidence: Float = 1f,
    ): MetadataCandidate {
        val region = query.region
        val year = dates.pick(region)?.take(4)?.toIntOrNull()

        return MetadataCandidate(
            providerId = ID,
            remoteId = id?.toString().orEmpty(),
            matchedTitle = names.pick(region) ?: query.title,
            confidence = confidence,
            metadata = GameMetadata(
                description = synopsis.pickLanguage(),
                genres = genres.orEmpty().mapNotNull { it.names.pickLanguage() }.distinct(),
                developer = developer?.text,
                publisher = publisher?.text,
                releaseDate = dates.pick(region),
                releaseYear = year,
                // ScreenScraper rates out of 20.
                rating = rating?.text?.toIntOrNull()?.let { (it * 5).coerceIn(0, 100) },
                players = players?.text,
                region = region,
                providerSources = buildMap {
                    if (synopsis.pickLanguage() != null) put(GameMetadata.FIELD_DESCRIPTION, ID)
                    if (!genres.isNullOrEmpty()) put(GameMetadata.FIELD_GENRES, ID)
                    if (developer != null) put(GameMetadata.FIELD_DEVELOPER, ID)
                    if (publisher != null) put(GameMetadata.FIELD_PUBLISHER, ID)
                    if (dates.pick(region) != null) put(GameMetadata.FIELD_RELEASE_DATE, ID)
                    if (rating != null) put(GameMetadata.FIELD_RATING, ID)
                },
            ),
            artwork = medias.orEmpty().toArtworkSet(region),
        )
    }

    /**
     * Maps ScreenScraper's flat media list onto THOR's artwork slots.
     *
     * The `type` strings are ScreenScraper's own vocabulary. Region-matched
     * media is preferred, then world, then anything — a Japanese box scan is
     * better than no box scan.
     */
    private fun List<SsMedia>.toArtworkSet(region: String?): ArtworkSet {
        fun pick(vararg types: String): String? = types.firstNotNullOfOrNull { type ->
            val matching = filter { it.type == type }
            matching.firstOrNull { it.region.equals(region, ignoreCase = true) }?.url
                ?: matching.firstOrNull { it.region in WORLD_REGIONS }?.url
                ?: matching.firstOrNull()?.url
        }

        return ArtworkSet(
            /*
             * The front of the box, and nothing that merely contains one.
             *
             * The fallbacks here used to be `box-2D-side` and `box-texture`, and
             * neither is a cover: the first is the *spine*, a tall thin strip,
             * and the second is the unfolded wraparound — front, spine and back
             * as one very wide image. Both were then drawn in a portrait cell
             * that crops to fill, which is why a handful of games came out
             * looking like a slice of something rather than a cover.
             *
             * `box-3D` is the same artwork photographed at an angle and is still
             * portrait, so it stands in where a flat scan is missing.
             */
            boxArt = pick("box-2D", "box-3D"),
            /*
             * Fanart, and only fanart.
             *
             * `ss` sat on the end of this list once and is a gameplay capture, so on the
             * majority of games — which have no fanart — the background behind the title was a
             * 4:3 frame stretched across a widescreen panel. `screenmarquee` replaced it and
             * is the same mistake in a different shape: a marquee is a small badge, roughly
             * square, and the very next line uses it as the *logo*. Stretched across the whole
             * panel it reads as the game's icon blown up behind the text, which is exactly
             * what it is.
             *
             * An empty slot is better, and it is what every other provider here already does:
             * the panel falls through to the platform's own hero, an image made to be a wide
             * backdrop. This is the single most visible thing on the information screen and it
             * should be key art or nothing.
             */
            hero = pick("fanart"),
            logo = pick("wheel", "wheel-hd", "screenmarquee"),
            /*
             * No icon. ScreenScraper has nothing square that is a picture of the game.
             *
             * This was `support-2D`, then `wheel-carbon-steel`, on the reasoning that they
             * are the closest things here to 1:1 — which is true and beside the point.
             * `support-2D` is a scan of the *cartridge or disc*: a photograph of grey
             * plastic with a small label on it, or a silver circle. It is 1:1, it is not an
             * icon of the game, and a grid filled with them is a shelf of media rather than
             * a library of titles. That is precisely what it looked like on the device.
             *
             * The square cell icon is SteamGridDB's, whose `grids` endpoint serves 1:1
             * images that are cover art *composed* for a square frame. Nothing else here
             * holds one, so this slot is left empty rather than filled with the nearest
             * shape to hand — an empty icon falls through to the box art, which is at least
             * a picture of the game.
             */
            icon = null,
            screenshots = pickWide(region),
            videoUri = pick("video-normalized", "video"),
        )
    }

    /**
     * Every wide image the entry has, best first, up to the model's cap.
     *
     * [toArtworkSet]'s `pick` answers "the one best media of this type", which is
     * right for a box scan and wrong for screenshots — it returned a single shot
     * however many the entry carried, so the panel had one image to cycle and the
     * strip looked broken. This keeps going instead: the region's own media
     * first, then world, then whatever is left, deduplicated because the same
     * shot is commonly registered under several regions.
     *
     * Ordered by type as well: `fanart` is the wide promotional still, `ss` the
     * in-game capture, and `sstitle` a title screen, which is the least
     * interesting of the three and so goes last rather than displacing anything.
     */
    private fun List<SsMedia>.pickWide(region: String?): List<String> {
        val ranked = WIDE_TYPES.flatMap { type ->
            val matching = filter { it.type == type }
            val regional = matching.filter { it.region.equals(region, ignoreCase = true) }
            val world = matching.filter { it.region in WORLD_REGIONS }
            (regional + world + matching).mapNotNull(SsMedia::url)
        }
        return ranked.distinct().take(ArtworkSet.MAX_SCREENSHOTS)
    }

    // ------------------------------------------------------------------ DTOs

    @Serializable
    private data class SsEnvelope(val response: SsResponse? = null)

    @Serializable
    private data class SsResponse(
        val jeu: SsGame? = null,
        /** `jeuRecherche.php` answers with a list where `jeuInfos.php` answers with one. */
        val jeux: List<SsGame>? = null,
    )

    @Serializable
    private data class SsGame(
        val id: Int? = null,
        val noms: List<SsRegionText>? = null,
        val synopsis: List<SsLanguageText>? = null,
        val editeur: SsText? = null,
        val developpeur: SsText? = null,
        val dates: List<SsRegionText>? = null,
        val genres: List<SsGenre>? = null,
        val note: SsText? = null,
        val joueurs: SsText? = null,
        val medias: List<SsMedia>? = null,
    ) {
        val names: List<SsRegionText>? get() = noms
        val publisher: SsText? get() = editeur
        val developer: SsText? get() = developpeur
        val rating: SsText? get() = note
        val players: SsText? get() = joueurs
    }

    @Serializable
    private data class SsGenre(val noms: List<SsLanguageText>? = null) {
        val names: List<SsLanguageText>? get() = noms
    }

    /**
     * A region-tagged value.
     *
     * `text` is declared as a raw [JsonElement] because ScreenScraper returns it
     * as a string in most places and as a number in a few (ratings, player
     * counts), and a strict `String` would fail the whole payload on those.
     */
    @Serializable
    private data class SsRegionText(
        val region: String? = null,
        val text: JsonElement? = null,
    ) {
        val value: String? get() = text?.asText()
    }

    @Serializable
    private data class SsLanguageText(
        val langue: String? = null,
        val text: JsonElement? = null,
    ) {
        val language: String? get() = langue
        val value: String? get() = text?.asText()
    }

    @Serializable
    private data class SsText(@SerialName("text") val raw: JsonElement? = null) {
        val text: String? get() = raw?.asText()
    }

    @Serializable
    private data class SsMedia(
        val type: String? = null,
        val url: String? = null,
        val region: String? = null,
    )

    private companion object {
        const val ID = "screenscraper"
        private const val TAG = "ScreenScraper"
        private const val BASE_URL = "https://api.screenscraper.fr/api2"

        /**
         * Landscape media only, best first.
         *
         * Every one of these is wider than it is tall, which is the whole
         * requirement: the strip is a sixteen-by-nine frame, and an image that
         * arrives portrait either letterboxes into slivers or crops to a
         * meaningless middle. `fanart` is key art and leads because it is drawn
         * rather than captured; `ss` and `sstitle` are frames from the game,
         * which fill the strip when there is no key art to be had.
         *
         * Box and flyer scans were briefly here and are the reason the panel
         * filled with mismatched shapes — they are portrait, whatever else they
         * are. They still reach the grid cell through the `boxArt` slot, which
         * is the frame shaped for them.
         */
        private val WIDE_TYPES = listOf("fanart", "screenmarquee", "ss", "sstitle")

        /** Identifies this client to ScreenScraper in its request logs. */
        private const val SOFT_NAME = "Loki"

        /**
         * Below this length a search term matches half the database.
         *
         * "Ico" is three characters and a real game, so this is deliberately not
         * set where a person would draw it — the endpoint is asked, and the score
         * below decides. One and two character titles are where it stops being a
         * question worth a network request.
         */
        private const val MIN_SEARCH_TERM = 3

        /**
         * How well a name has to match before it is offered at all.
         *
         * Above the aggregator's own floor, and for a reason: this is the one
         * path here that can be confidently wrong. A hash match cannot attach
         * the wrong game's cover and a name match can, so the bar for even
         * entering the merge is higher than the bar for surviving it.
         */
        private const val NAME_MATCH_FLOOR = 0.55f

        /**
         * How many name matches are worth carrying.
         *
         * Searching for "Mario" returns thirty games, each with its full media
         * list attached. All thirty are honest answers to what was asked and
         * twenty-seven of them are not the game — so they are parsing time, and
         * rows in the "which game is this?" dialog, spent on nothing.
         */
        private const val MAX_SEARCH_RESULTS = 5

        /**
         * The application's registered developer key, compiled in at build time.
         *
         * This is what identifies THOR to ScreenScraper. It belongs to the
         * application, not to the person using it, which is why no launcher asks
         * the user for it — see the `thor.screenscraper.*` gradle properties.
         */
        private val DEV_ID: String = BuildConfig.SCREENSCRAPER_DEV_ID
        private val DEV_PASSWORD: String = BuildConfig.SCREENSCRAPER_DEV_PASSWORD

        private val hasDeveloperKey: Boolean
            get() = DEV_ID.isNotBlank() && DEV_PASSWORD.isNotBlank()

        private val WORLD_REGIONS = setOf("wor", "world", "us", "eu")

        /** Preferred description languages, best first. */
        private val LANGUAGES = listOf("en", "us", "wor")

        /** Region-preference order when no query region is known. */
        private fun List<SsRegionText>?.pick(region: String?): String? {
            val list = this ?: return null
            val wanted = region?.lowercase()?.take(2)
            return list.firstOrNull { it.region?.lowercase() == wanted }?.value
                ?: list.firstOrNull { it.region in WORLD_REGIONS }?.value
                ?: list.firstOrNull()?.value
        }

        private fun List<SsLanguageText>?.pickLanguage(): String? {
            val list = this ?: return null
            return LANGUAGES.firstNotNullOfOrNull { language ->
                list.firstOrNull { it.language?.lowercase() == language }?.value
            } ?: list.firstOrNull()?.value
        }

        /** Reads a JSON value that may be a string or a number. */
        private fun JsonElement.asText(): String? = (this as? JsonPrimitive)
            ?.let { primitive -> primitive.content.takeIf { it.isNotBlank() } }
    }
}
