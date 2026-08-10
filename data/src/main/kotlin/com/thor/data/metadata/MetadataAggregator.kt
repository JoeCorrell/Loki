package com.thor.data.metadata

import com.thor.core.common.dispatchers.Dispatcher
import com.thor.core.common.dispatchers.ThorDispatcher
import com.thor.core.common.log.ThorLog
import com.thor.core.common.text.truncateToSentences
import com.thor.core.datastore.SettingsRepository
import com.thor.core.model.ArtworkSet
import com.thor.core.model.GameMetadata
import com.thor.core.model.MetadataSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Queries every enabled provider and merges the results into one record.
 *
 * The merge is field-by-field rather than "best provider wins wholesale",
 * because no single provider is best at everything: ScreenScraper answers almost
 * all of it, SteamGridDB holds the one square image a cell wants, and Wikidata
 * needs no account at all. For each field the winner is the highest-priority
 * provider that actually supplied a value, with two hard rules on top:
 *
 *  - a candidate below [MIN_CONFIDENCE] is discarded entirely, so a bad title
 *    match cannot contribute even one field;
 *  - fields the user has edited are never overwritten.
 */
@Singleton
class MetadataAggregator @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards MetadataProvider>,
    private val settings: SettingsRepository,
    private val artworkStore: ArtworkStore,
    @Dispatcher(ThorDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Scrapes metadata for one game.
     *
     * @param existing the current record, whose locked fields are preserved
     * @return the merged result, or `existing` when nothing usable was found
     */
    suspend fun scrape(
        query: MetadataQuery,
        existing: GameMetadata,
        /**
         * Whether freshly fetched artwork supersedes what is already stored.
         *
         * False for a top-up pass, which only fills gaps. True for a full
         * re-scrape, because otherwise that action cannot change anything: every
         * artwork slot was written on the first pass and kept forever after, so
         * re-scraping a library whose images came from a provider since replaced
         * returned exactly the images complained about. A locked field and a
         * hand-picked image are still untouchable either way.
         */
        replaceArtwork: Boolean = false,
    ): GameMetadata =
        withContext(ioDispatcher) {
            val config = settings.metadata.first()
            val active = usableProviders(config)

            if (active.isEmpty()) {
                ThorLog.d(TAG) { "No configured providers; skipping '${query.title}'" }
                return@withContext existing
            }

            // The confidence floor still applies: a weak title match must not contribute even
            // one field, which is what going through the same filter as every other route
            // guarantees.
            val usable = queryProviders(active, query, config)
                .filter { it.confidence >= MIN_CONFIDENCE }
            if (usable.isEmpty()) return@withContext existing

            keepArtwork(merge(existing, usable, config, replaceArtwork), config)
        }

    /**
     * Copies freshly chosen artwork onto the device, replacing remote URLs with local files.
     *
     * Done here rather than at each call site so every route into a scrape gets it: the library
     * pass, a single game, the manual picker and the folder scrape all end up in this class, and
     * one of them missing it would produce a library where some entries survive the source going
     * away and others do not.
     */
    private suspend fun keepArtwork(merged: GameMetadata, config: MetadataSettings): GameMetadata {
        if (!config.keepArtworkOnDevice) return merged
        val stored = artworkStore.localise(merged.artwork)
        return if (stored == merged.artwork) merged else merged.copy(artwork = stored)
    }

    /**
     * The second half of [scrape], for a caller that already has the candidates.
     *
     * Split out so asking the user which game this is does not cost a second
     * round of provider requests: the interactive path searches once, shows what
     * came back, and then merges either the answer or — if nobody answered — this
     * same automatic result. Filtering by [MIN_CONFIDENCE] belongs here rather
     * than at the search, because a person can recognise a match a score cannot
     * and the picker is shown the unfiltered set.
     */
    suspend fun mergeCandidates(
        candidates: List<MetadataCandidate>,
        existing: GameMetadata,
        replaceArtwork: Boolean = false,
    ): GameMetadata = withContext(ioDispatcher) {
        val usable = candidates.filter { it.confidence >= MIN_CONFIDENCE }
        if (usable.isEmpty()) return@withContext existing
        val config = settings.metadata.first()
        keepArtwork(merge(existing, usable, config, replaceArtwork), config)
    }

    /**
     * Every candidate the providers offer, for the user to choose between.
     *
     * The automatic path takes the highest-confidence candidate per field and
     * merges. This returns the whole set instead, unfiltered by
     * [MIN_CONFIDENCE], because the point of asking a person is that they can
     * recognise the right answer where the score could not — and a threshold
     * that hid it would defeat the exercise. Deduplicated by provider and title
     * so one game matched by four providers is one row rather than four.
     */
    suspend fun candidates(query: MetadataQuery): List<MetadataCandidate> =
        withContext(ioDispatcher) {
            val config = settings.metadata.first()
            val active = usableProviders(config)
            if (active.isEmpty()) return@withContext emptyList()

            queryProviders(active, query, config)
                .distinctBy { it.providerId to it.matchedTitle.lowercase() }
                .sortedByDescending { it.confidence }
        }

    /**
     * Applies one chosen candidate over what is already stored.
     *
     * Locks every field it fills, which is the whole point of having chosen it:
     * a hand-picked match that the next scrape quietly replaced would be worse
     * than no picker at all.
     */
    suspend fun applyChosen(existing: GameMetadata, chosen: MetadataCandidate): GameMetadata =
        withContext(ioDispatcher) {
            val config = settings.metadata.first()
            val merged = keepArtwork(
                merge(existing, listOf(chosen), config, replaceArtwork = true),
                config,
            )
            merged.copy(
                lockedFields = merged.lockedFields + buildSet {
                    if (!merged.description.isNullOrBlank()) add(GameMetadata.FIELD_DESCRIPTION)
                    if (merged.genres.isNotEmpty()) add(GameMetadata.FIELD_GENRES)
                    if (!merged.developer.isNullOrBlank()) add(GameMetadata.FIELD_DEVELOPER)
                    if (!merged.publisher.isNullOrBlank()) add(GameMetadata.FIELD_PUBLISHER)
                    if (merged.releaseYear != null) add(GameMetadata.FIELD_RELEASE_DATE)
                    if (merged.rating != null) add(GameMetadata.FIELD_RATING)
                    if (merged.artwork != existing.artwork) add(GameMetadata.FIELD_ARTWORK)
                },
            )
        }

    /** True when at least one enabled provider is fully configured. */
    suspend fun hasUsableProvider(): Boolean =
        usableProviders(settings.metadata.first()).isNotEmpty()

    /**
     * Whether any configured provider can return a trailer at all.
     *
     * Only two of them ever do, and both need credentials. Without this check a
     * trailer refresh looked like it worked: Wikidata needs no key, so it counts
     * as a usable provider, the pass ran happily through the whole library asking
     * a source that has never returned a video, and reported "0 updated" — which
     * reads as "your games have no trailers" rather than "nothing here can fetch
     * one". That is the difference between a library problem and a settings
     * problem, and the user has no way to tell them apart from the outside.
     */
    suspend fun hasTrailerProvider(): Boolean =
        usableProviders(settings.metadata.first()).any { it.id in TRAILER_PROVIDERS }

    /**
     * Whether any usable provider can supply text, not just artwork.
     *
     * Worth reporting separately because the two failure modes look identical from
     * the grid and completely different in cause: with only SteamGridDB
     * configured, a scrape downloads artwork for the whole library and fills in no
     * developer, publisher, description or genre at all — which reads as a broken
     * scraper rather than as a provider that never offered those fields.
     */
    suspend fun hasTextualProvider(): Boolean =
        usableProviders(settings.metadata.first()).any { !it.artworkOnly }

    /** True when a configured source can fill the game-description field. */
    suspend fun hasDescriptionProvider(): Boolean =
        usableProviders(settings.metadata.first()).any { it.id in DESCRIPTION_PROVIDERS }

    /** True when a configured source can supply landscape images of a game. */
    suspend fun hasScreenshotProvider(): Boolean =
        usableProviders(settings.metadata.first()).any { it.id in SCREENSHOT_PROVIDERS }

    /**
     * True when a configured source knows how long a game takes to finish.
     *
     * The narrowest of these lists, and worth asking separately for that reason:
     * ScreenScraper covers the retro library almost completely and has no
     * completion field at all, so the usual configuration scrapes every game
     * successfully and produces no progress bars whatsoever. That looks like a
     * broken panel rather than a provider that was never asked.
     */
    suspend fun hasCompletionProvider(): Boolean =
        usableProviders(settings.metadata.first()).any { it.id in COMPLETION_PROVIDERS }

    /**
     * Whether one named provider could issue a request right now.
     *
     * Asked of the provider rather than inferred from the settings map, because
     * what a provider needs is its own business — ScreenScraper is gated on
     * credentials compiled into the build, which no amount of reading the user's
     * settings would reveal.
     */
    suspend fun isProviderConfigured(id: String): Boolean =
        providers.firstOrNull { it.id == id }?.isConfigured() == true

    /**
     * Probes every provider, concurrently, and reports what each one said.
     *
     * @return provider id to its status
     */
    suspend fun checkConnections(): Map<String, ProviderStatus> =
        withContext(ioDispatcher) {
            coroutineScope {
                providers
                    .map { provider ->
                        async {
                            provider.id to runCatching { provider.checkConnection() }
                                .getOrElse { ProviderStatus.Error(it.message ?: "Failed") }
                        }
                    }
                    .awaitAll()
                    .toMap()
            }
        }

    /**
     * Enabled providers that can actually issue a request.
     *
     * Each provider is asked rather than having its requirements inferred from
     * the settings map — ScreenScraper is configured by a developer pair, not an
     * API key, and a central check would report it unusable.
     */
    private suspend fun usableProviders(config: MetadataSettings): List<MetadataProvider> =
        providers
            .filter { it.id in config.enabledProviders }
            .filter { it.isConfigured() }

    /**
     * Runs providers concurrently, bounded by the user's request cap so a large
     * library scrape does not open dozens of sockets at once.
     */
    private suspend fun queryProviders(
        active: List<MetadataProvider>,
        query: MetadataQuery,
        config: MetadataSettings,
    ): List<MetadataCandidate> = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT_REQUESTS)
        active
            .map { provider ->
                async {
                    gate.withPermit {
                        runCatching { provider.search(query) }
                            .onFailure { ThorLog.w(TAG, "Provider ${provider.id} failed", it) }
                            .getOrDefault(emptyList())
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    /**
     * Field-wise merge.
     *
     * Candidates are sorted so the most trustworthy is consulted first, then
     * each field takes the first non-empty value found.
     */
    private fun merge(
        existing: GameMetadata,
        candidates: List<MetadataCandidate>,
        config: MetadataSettings,
        replaceArtwork: Boolean = false,
    ): GameMetadata {
        /*
         * Confident matches as a band, then priority inside it.
         *
         * Priority alone decided this, which meant a preferred provider's weak
         * match outranked another's exact one — a 0.5 guess from the top of the
         * list beat a 1.0 hit from the bottom, and the game got somebody else's
         * cover. Priority is the right tie-breaker between two providers that
         * both found the game; it is the wrong way to choose between one that did
         * and one that did not.
         */
        val ranked = candidates.sortedWith(
            compareByDescending<MetadataCandidate> { it.confidence >= STRONG_CONFIDENCE }
                .thenBy { config.providerPriority[it.providerId] ?: Int.MAX_VALUE }
                .thenByDescending { it.confidence },
        )

        // Artwork-only providers must not win textual fields even when they
        // rank highly; they still compete for the artwork slots below.
        val artworkOnlyIds = providers.filter(MetadataProvider::artworkOnly).map(MetadataProvider::id).toSet()
        val textual = ranked.filterNot { it.providerId in artworkOnlyIds }

        val locked = existing.lockedFields
        fun <T> pick(field: String, current: T?, selector: (MetadataCandidate) -> T?): T? =
            if (field in locked) current else current ?: textual.firstNotNullOfOrNull(selector)
        /*
         * `preferred` names the provider that owns this field when it answers.
         *
         * The description is Wikipedia's: it writes a paragraph about the game
         * rather than a marketing blurb, and it is the only source here with no
         * credential to go missing. Everything else falls through to the ranking.
         */
        fun pickText(
            field: String,
            current: String?,
            preferred: String? = null,
            selector: (MetadataCandidate) -> String?,
        ): String? = selectNonBlankMetadataText(
            current = current,
            locked = field in locked,
            candidates = (preferred?.let(textual::preferring) ?: textual).map(selector),
        )

        val sources = existing.providerSources.toMutableMap()
        textual.forEach { candidate ->
            candidate.metadata.providerSources.forEach { (field, providerId) ->
                if (field !in locked) sources.putIfAbsent(field, providerId)
            }
        }

        val mergedArtwork = mergeArtwork(existing.artwork, ranked, locked, replaceArtwork)
        if (mergedArtwork != existing.artwork) {
            sources.putIfAbsent(
                GameMetadata.FIELD_ARTWORK,
                ranked.firstOrNull { !it.artwork.isEmpty }?.providerId ?: "",
            )
        }

        return existing.copy(
            description = pickText(
                GameMetadata.FIELD_DESCRIPTION,
                existing.description,
                preferred = DESCRIPTION_PROVIDER,
            ) {
                /*
                 * Capped here, inside the selector, so it applies to what a
                 * provider offers and never to what is already stored. A
                 * description the user typed in the editor is `existing` and is
                 * returned untouched; shortening that would be editing their
                 * writing on their behalf.
                 */
                it.metadata.description?.truncateToSentences(DESCRIPTION_MAX_CHARS)
            },
            genres = if (GameMetadata.FIELD_GENRES in locked || existing.genres.isNotEmpty()) {
                existing.genres
            } else {
                textual.firstOrNull { it.metadata.genres.isNotEmpty() }?.metadata?.genres.orEmpty()
            },
            developer = pickText(GameMetadata.FIELD_DEVELOPER, existing.developer) {
                it.metadata.developer
            },
            publisher = pickText(GameMetadata.FIELD_PUBLISHER, existing.publisher) {
                it.metadata.publisher
            },
            releaseDate = pick(GameMetadata.FIELD_RELEASE_DATE, existing.releaseDate) {
                it.metadata.releaseDate
            },
            releaseYear = pick(GameMetadata.FIELD_RELEASE_DATE, existing.releaseYear) {
                it.metadata.releaseYear
            },
            rating = pick(GameMetadata.FIELD_RATING, existing.rating) { it.metadata.rating },
            players = pick(GameMetadata.FIELD_PLAYERS, existing.players) { it.metadata.players },
            completionMinutes = existing.completionMinutes
                ?: textual.firstNotNullOfOrNull { it.metadata.completionMinutes },
            /*
             * Kept once found, like the completion figure beside it.
             *
             * Only one provider reports these and only for part of its
             * catalogue, so a later scrape whose winning candidate happens to be
             * from somewhere else would otherwise erase a figure nothing else
             * can supply.
             */
            timeToBeat = existing.timeToBeat
                ?: textual.firstNotNullOfOrNull { it.metadata.timeToBeat },
            achievements = existing.achievements,
            artwork = mergedArtwork,
            providerSources = sources,
            lastScrapedEpochMs = System.currentTimeMillis(),
        )
    }

    /**
     * Artwork merges per slot rather than per provider, so a game can take its
     * hero from SteamGridDB and its screenshots from ScreenScraper.
     *
     * One cover and at most [ArtworkSet.MAX_SCREENSHOTS] screenshots. Providers
     * will return dozens; beyond a handful nobody looks at them, and each one is
     * a download, a cache entry and another step in the information screen's
     * slideshow — so the set is capped here, at ingestion, rather than trimmed
     * every time it is drawn.
     */
    private fun mergeArtwork(
        existing: ArtworkSet,
        ranked: List<MetadataCandidate>,
        locked: Set<String>,
        replaceArtwork: Boolean,
    ): ArtworkSet {
        if (GameMetadata.FIELD_ARTWORK in locked) return existing

        // On a full re-scrape the fetched image wins where there is one; on a
        // top-up the stored one does. Either way a slot nobody answered keeps
        // what it had, so a provider being down never blanks a library.
        fun slot(current: String?, fetched: String?): String? =
            if (replaceArtwork) fetched ?: current else current ?: fetched

        /*
         * Each slot goes to whoever is best at it, not to whoever ranks highest
         * overall.
         *
         * A single priority order cannot say this. ScreenScraper leads and should
         * win the cover, the backdrop and the screenshots, while the square grid
         * a cell wants is SteamGridDB's and nobody else holds one. Ranking
         * ScreenScraper first outright would hand it a slot whose only candidate
         * on its side is a photograph of a cartridge; ranking it below
         * SteamGridDB to avoid that would cost it everything it should win. So
         * the preference is per slot, and the general order decides the rest.
         */
        val forArtwork = ranked.preferring(ARTWORK_PROVIDER)
        val forIcon = ranked.preferring(ICON_PROVIDER)

        return ArtworkSet(
            boxArt = slot(existing.boxArt, forArtwork.firstNotNullOfOrNull { it.artwork.boxArt }),
            /*
             * The background and the cell image take the fetched answer whenever there is one,
             * even on a top-up.
             *
             * Every other slot is a choice between two acceptable pictures, where keeping what
             * is stored avoids needless churn. These two are different: what matters is the
             * *kind* of image, and a library scraped before the rules changed is full of
             * backgrounds that are gameplay screenshots and cells that are cartridge
             * photographs. Under `current ?: fetched` those could never be repaired — "only
             * fill in missing data" means the slot is not missing, so it is skipped forever, and
             * the fix would only reach somebody who knew to run a full re-scrape.
             *
             * A hand-picked image is still untouchable: `FIELD_ARTWORK in locked` returns above
             * before any of this runs.
             */
            /*
             * On a full re-scrape the fetched answer wins even when it is *nothing*.
             *
             * `?: existing.hero` was the hole in the repair this comment describes. A slot
             * holding the wrong kind of image could be overwritten by a better one, but never
             * emptied — so a library carrying marquees and gameplay frames from an older rule
             * kept them for as long as no provider happened to return a hero, which for a game
             * with no fanart is forever. The panel went on showing a badge stretched behind the
             * title and no amount of re-scraping moved it.
             *
             * Clearing it is the repair. An empty hero is not a loss: the panel falls through
             * to the platform's own, which is an image made to be a backdrop.
             *
             * A top-up still keeps what is stored — it is not asking to be corrected — and a
             * hand-picked image never reaches here at all; `FIELD_ARTWORK in locked` returns
             * above.
             */
            hero = if (replaceArtwork) {
                forArtwork.firstNotNullOfOrNull { it.artwork.hero }
            } else {
                existing.hero ?: forArtwork.firstNotNullOfOrNull { it.artwork.hero }
            },
            logo = slot(existing.logo, ranked.firstNotNullOfOrNull { it.artwork.logo }),
            /*
             * On a full re-scrape the fetched answer wins even when it is *nothing*.
             *
             * `?: existing.icon` was the same hole the hero slot had, and it mattered for
             * the same reason. ScreenScraper used to fill this from `support-2D` — a
             * photograph of the cartridge or disc — so a library scraped under that rule
             * is full of grey plastic where the game's cover should be. Now that the
             * provider returns no icon at all, "keep what is stored when nobody answers"
             * means those images can never be replaced: no source offers an icon for a
             * game SteamGridDB has not heard of, so the cartridge stays for good and no
             * amount of re-scraping moves it.
             *
             * Clearing it is the repair, and it is not a loss — an empty icon falls
             * through to the box art, which is at least a picture of the game.
             *
             * A top-up still keeps what is stored; it is not asking to be corrected. A
             * hand-picked image never reaches here at all, because `FIELD_ARTWORK in
             * locked` returns above.
             */
            icon = if (replaceArtwork) {
                forIcon.firstNotNullOfOrNull { it.artwork.icon }
            } else {
                existing.icon ?: forIcon.firstNotNullOfOrNull { it.artwork.icon }
            },
            /*
             * Topped up, not replaced and not skipped.
             *
             * `ifEmpty` here meant a game that already had a single shot kept
             * that one for good: the branch only ran when there were none, so
             * re-scraping a library that had been through a provider returning
             * one image could never reach three however many providers were
             * added afterwards. Existing shots stay at the front, so nothing the
             * user is looking at reshuffles, and the rest of the room is filled
             * from whoever has more.
             */
            screenshots = when {
                // Fetched first on a re-scrape, so a full set of stale images
                // cannot fill the cap and shut the new ones out.
                replaceArtwork ->
                    (forArtwork.flatMap { it.artwork.screenshots } + existing.screenshots)
                else -> (existing.screenshots + forArtwork.flatMap { it.artwork.screenshots })
            }
                .distinct()
                .take(ArtworkSet.MAX_SCREENSHOTS),
            videoUri = existing.videoUri ?: ranked.firstNotNullOfOrNull { it.artwork.videoUri },
            dominantArgb = existing.dominantArgb,
        )
    }

    private companion object {
        const val TAG = "Metadata"

        /**
         * The providers that carry video.
         *
         * ScreenScraper alone, now: it ships clips for retro titles, which is the
         * library this launcher is for. SteamGridDB is artwork only and Wikidata
         * is facts only — neither has a video field to read.
         */
        val TRAILER_PROVIDERS = setOf("screenscraper")

        /**
         * Sources whose payloads contain prose rather than facts or artwork only.
         *
         * Wikidata is here as well as ScreenScraper because it needs no account
         * of any kind: with everything signed out, it is still the difference
         * between "no provider can do this" and "this game has no write-up".
         */
        val DESCRIPTION_PROVIDERS = setOf("screenscraper", "wikidata")

        /**
         * Sources that carry landscape images of a game.
         *
         * Not the same question as "has artwork". SteamGridDB has plenty and
         * none of it is this shape — a grid is portrait or square and a hero is
         * an ultra-wide banner — so a launcher configured with SteamGridDB alone
         * fills every cover and leaves the panel with nothing to show.
         */
        val SCREENSHOT_PROVIDERS = setOf("screenscraper")

        /**
         * Sources that know how long a game takes.
         *
         * Empty, and deliberately still asked. IGDB had submitted play-throughs
         * and RAWG an average in whole hours, and both have been removed — so
         * nothing the launcher ships carries this figure. Keeping the question
         * means [hasCompletionProvider] answers false and the sync manager stops
         * re-scraping games to fill a field that cannot be filled, rather than
         * asking every provider about it once per game forever.
         *
         * A set rather than a `false`, because this is a fact about which sources
         * are installed and not a decision. Adding one back is adding its id.
         */
        val COMPLETION_PROVIDERS = emptySet<String>()

        /**
         * Below this, a title match is more likely to be a different game than
         * the right one — attaching wrong artwork is worse than attaching none.
         */
        const val MIN_CONFIDENCE = 0.45f

        /**
         * At or above this, a match is treated as certainly the right game.
         *
         * Only used for ordering, not for rejecting: everything above
         * [MIN_CONFIDENCE] is still allowed to contribute, but a provider that is
         * sure gets asked before one that is merely preferred.
         */
        const val STRONG_CONFIDENCE = 0.85f

        /**
         * Who owns a slot when they answer at all.
         *
         * Stated rather than derived from the priority order, because the order
         * is one list and these are three different questions. ScreenScraper has
         * the artwork and the prose, SteamGridDB the square grid a cell wants —
         * and no single ranking puts the right source first for all of them.
         */
        /* The three slot owners are declared at the foot of this file. */

        /**
         * How much synopsis is worth keeping.
         *
         * Wikipedia is asked for a few sentences and obliges; the others hand
         * over whatever their page holds, and ScreenScraper in particular will
         * return several hundred words of plot for a platformer. The information
         * panel is a few square inches and has to make what it is given fit, so
         * everything past roughly a short paragraph was only ever going to be
         * dropped at the far end — better to not carry it in the first place
         * than to store an essay and shrink it on every frame.
         *
         * Cut on a sentence, never at the character, so what is stored reads as
         * something somebody wrote rather than as something that ran out.
         */
        const val DESCRIPTION_MAX_CHARS = 600

        /**
         * Concurrent provider requests per game.
         *
         * Fixed rather than user-tunable: there are only ever three providers to
         * ask, and every one of them rate limits, so the useful range was one
         * value wide.
         */
        const val MAX_CONCURRENT_REQUESTS = 3
    }
}

/**
 * Empty strings were persisted by early imports and metadata editors. Treat
 * those as missing so a later scrape can actually repair the field, while
 * still respecting a user's explicit field lock.
 */
internal fun selectNonBlankMetadataText(
    current: String?,
    locked: Boolean,
    candidates: List<String?>,
): String? {
    if (locked) return current
    return current?.takeIf(String::isNotBlank)
        ?: candidates.firstNotNullOfOrNull { it?.takeIf(String::isNotBlank) }
}

/**
 * Moves one provider's candidates to the front, leaving the rest in order.
 *
 * A stable sort, so this expresses "ask this one first" rather than reordering
 * anything else: the preferred provider gets first refusal on the slot, and if
 * it has nothing the ranking decides exactly as it did before. Nothing is
 * excluded — a preference is not a requirement, and a game the preferred source
 * has never heard
 * of still gets whatever artwork anybody else found.
 */
internal fun List<MetadataCandidate>.preferring(providerId: String): List<MetadataCandidate> =
    sortedByDescending { it.providerId == providerId }

/*
 * ---- Who owns which slot ---------------------------------------------------
 *
 * Top level and internal rather than private constants on the companion, so the
 * test that asserts these route to three different providers can name them. It
 * used to hold its own copy of the three strings and had already drifted out of
 * step with them — a mirror that claims a rename will break it loudly, and does
 * not.
 */

/*
 * ScreenScraper owns the artwork slots when it answers.
 *
 * It identifies the file by hash and returns art already ordered by region against
 * that identification, so its cover is the right region's cover rather than merely
 * the right-shaped one. It also has the deepest catalogue for these systems and a
 * real `fanart` type, which is what the top screen wants behind a title.
 *
 * A preference, not a requirement. SteamGridDB still competes for any slot
 * ScreenScraper leaves empty, which for a game it has never heard of is all of
 * them — a cover from somewhere is better than a blank cell.
 */
internal const val ARTWORK_PROVIDER = "screenscraper"

/**
 * The square cell icon, which is SteamGridDB's and only SteamGridDB's.
 *
 * The one slot ScreenScraper is not the answer to, and not for want of ranking:
 * it has no square image that is a picture of the *game*. The closest it holds
 * is `support-2D`, a scan of the cartridge or disc — 1:1, and a photograph of
 * grey plastic. A grid of those is a shelf of media rather than a library of
 * titles, so [ScreenScraperProvider] returns no icon at all now and this
 * preference has nothing left to outrank.
 *
 * SteamGridDB's `grids` endpoint asked at 1:1 returns cover art *composed* for a
 * square frame, which is the thing being asked for. Its API key is therefore the
 * one credential that still buys something no other source here can supply; with
 * no key the slot stays empty and a cell falls through to the box art.
 */
internal const val ICON_PROVIDER = "steamgriddb"

/*
 * The synopsis, now ScreenScraper's as well.
 *
 * This was Wikidata's on the grounds that it writes prose about the game rather
 * than a marketing blurb, which is true and was the right call while ScreenScraper
 * was ranked below the sources that fill this in. It is the wrong call for a
 * launcher whose leading source is ScreenScraper: its synopsis is written per
 * system and per region and describes the game as the platform's audience met it,
 * where Wikipedia's opening paragraph is as often about the game's development or
 * its reception.
 *
 * Wikidata still fills this in wherever ScreenScraper has no synopsis, which for
 * anything obscure is often. Both are cut to [DESCRIPTION_MAX_CHARS] on a sentence.
 */
internal const val DESCRIPTION_PROVIDER = "screenscraper"
