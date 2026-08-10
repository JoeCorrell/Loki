package com.thor.core.datastore

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.ThorSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Schema 1 → 2: ScreenScraper becoming the launcher's leading metadata source.
 *
 * This migration exists because the settings document stores its own copy of
 * every default — `encodeDefaults` is on — so changing which providers a scrape
 * consults in the model reaches a fresh install and nothing else. Every device
 * that has ever saved its settings would go on scraping exactly as it did
 * before, with no error anywhere to say why.
 */
class MetadataSourceMigrationTest {

    private val serializer = SettingsSerializer()

    /** A document from the previous schema, with the ordering it shipped with. */
    private val schemaOne = """
        {
          "schemaVersion": 1,
          "metadata": {
            "enabledProviders": ["artscraper", "screenscraper", "steamgriddb", "wikidata", "rawg", "igdb"],
            "providerPriority": {
              "artscraper": 0,
              "screenscraper": 1,
              "steamgriddb": 2,
              "wikidata": 3,
              "igdb": 4,
              "rawg": 5
            },
            "screenScraperUser": "someone",
            "screenScraperPassword": "secret"
          }
        }
    """.trimIndent()

    private suspend fun load(document: String): ThorSettings =
        serializer.readFrom(ByteArrayInputStream(document.toByteArray()))

    @Test
    fun `an existing install is moved onto ScreenScraper`() = runTest {
        val settings = load(schemaOne)

        assertThat(settings.metadata.providerPriority["screenscraper"]).isEqualTo(0)
        assertThat(settings.metadata.enabledProviders)
            .containsExactly("screenscraper", "steamgriddb", "wikidata")
    }

    /**
     * The account survives the migration.
     *
     * The whole document is decoded and only the two provider fields are
     * rewritten. Losing a signed-in ScreenScraper account while switching *to*
     * ScreenScraper would be the one way to make this change actively worse than
     * not making it.
     */
    @Test
    fun `the user's own credentials are untouched`() = runTest {
        val settings = load(schemaOne)

        assertThat(settings.metadata.screenScraperUser).isEqualTo("someone")
        assertThat(settings.metadata.screenScraperPassword).isEqualTo("secret")
    }

    @Test
    fun `the document is stamped so the migration runs once`() = runTest {
        val settings = load(schemaOne)

        assertThat(settings.schemaVersion).isEqualTo(ThorSettings.CURRENT_SCHEMA_VERSION)
    }

    /**
     * The removed sources are gone from the stored ordering too.
     *
     * ArtScraper, IGDB and RAWG no longer exist as providers, so an id left in
     * this map would name nothing — a priority for a source that cannot be
     * bound, carried forward on every device for as long as the file lives.
     */
    @Test
    fun `the removed sources are cleared out of the ordering`() = runTest {
        val priority = load(schemaOne).metadata.providerPriority

        assertThat(priority.keys).containsExactly("screenscraper", "steamgriddb", "wikidata")
    }

    /** Already migrated, so nothing is rewritten a second time. */
    @Test
    fun `a current document is left alone`() = runTest {
        val current = """
            {
              "schemaVersion": ${ThorSettings.CURRENT_SCHEMA_VERSION},
              "metadata": { "enabledProviders": ["rawg"] }
            }
        """.trimIndent()

        assertThat(load(current).metadata.enabledProviders).containsExactly("rawg")
    }
}
