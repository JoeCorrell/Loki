package com.thor.data.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which provider owns which slot.
 *
 * A single priority order cannot express it. ScreenScraper leads and takes the
 * artwork and the synopsis, but the square grid a cell wants is SteamGridDB's
 * and nobody else has one — ranking ScreenScraper high enough to win everything
 * would hand it a slot it has no asset for, and ranking it below SteamGridDB to
 * avoid that would cost it the two it should win.
 */
class ProviderPreferenceTest {

    private fun candidate(providerId: String, confidence: Float = 1f) =
        MetadataCandidate(
            providerId = providerId,
            remoteId = providerId,
            matchedTitle = providerId,
            confidence = confidence,
        )

    private val ranked = listOf(
        candidate("screenscraper"),
        candidate("steamgriddb"),
        candidate("wikidata"),
        candidate("igdb"),
    )

    @Test
    fun `the preferred provider is asked first`() {
        assertThat(ranked.preferring("igdb").first().providerId).isEqualTo("igdb")
        assertThat(ranked.preferring("steamgriddb").first().providerId).isEqualTo("steamgriddb")
    }

    @Test
    fun `everyone else keeps their order behind it`() {
        // A preference reorders one entry, not the list. Whatever the ranking
        // decided still decides everything the preference does not.
        assertThat(ranked.preferring("igdb").map { it.providerId })
            .containsExactly("igdb", "screenscraper", "steamgriddb", "wikidata")
            .inOrder()
    }

    @Test
    fun `nobody is excluded, because a preference is not a requirement`() {
        // A game IGDB has never heard of still gets whatever anyone else found.
        assertThat(ranked.preferring("igdb")).hasSize(ranked.size)
    }

    @Test
    fun `preferring an absent provider changes nothing`() {
        val withoutIgdb = ranked.filterNot { it.providerId == "igdb" }

        assertThat(withoutIgdb.preferring("igdb").map { it.providerId })
            .isEqualTo(withoutIgdb.map { it.providerId })
    }

    /**
     * The slots route where the aggregator says, not where this test says.
     *
     * Named against the real constants rather than a copy of the three strings.
     * This held its own copy and had already drifted a full provider out of
     * step with them — which is the failure mode of a mirror that claims a
     * rename will break it loudly and cannot.
     */
    @Test
    fun `artwork and prose go to ScreenScraper, the square icon to SteamGridDB`() {
        assertThat(ranked.preferring(ARTWORK_PROVIDER).first().providerId)
            .isEqualTo("screenscraper")
        assertThat(ranked.preferring(DESCRIPTION_PROVIDER).first().providerId)
            .isEqualTo("screenscraper")

        // The one slot that does not follow the leading provider, because it is
        // the one asset ScreenScraper does not have.
        assertThat(ranked.preferring(ICON_PROVIDER).first().providerId)
            .isEqualTo("steamgriddb")
    }

    /**
     * Every provider the launcher ships is ordered, and only those.
     *
     * An id here that names no provider is a priority for something that cannot
     * be bound; a provider missing from the map sorts to `Int.MAX_VALUE`, behind
     * everything, which is never what anyone meant.
     */
    @Test
    fun `the priority map names exactly the providers that exist`() {
        val priorities = com.thor.core.model.ThorSettings.DEFAULT.metadata.providerPriority

        assertThat(priorities.keys)
            .containsExactly("screenscraper", "steamgriddb", "wikidata")
        assertThat(priorities["screenscraper"]).isEqualTo(0)
    }

    @Test
    fun `the default scrape consults ScreenScraper first and drops the redundant sources`() {
        val enabled = com.thor.core.model.ThorSettings.DEFAULT.metadata.enabledProviders

        assertThat(enabled).containsExactly("screenscraper", "steamgriddb", "wikidata")
    }
}
