package com.thor.feature.settings.page

import androidx.compose.runtime.Composable
import com.thor.core.model.ThorSettings
import com.thor.data.metadata.ProviderStatus
import com.thor.data.sync.ScrapeState
import com.thor.feature.settings.component.row.ActionRow
import com.thor.feature.settings.component.row.InfoRow
import com.thor.feature.settings.component.RowDivider
import com.thor.feature.settings.component.row.SwitchRow
import com.thor.feature.settings.component.row.TextFieldRow
import com.thor.feature.settings.SettingsViewModel

@Composable
internal fun MetadataPage(
    settings: ThorSettings,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    scrapeState: ScrapeState,
    providerStatus: Map<String, ProviderStatus>,
    checking: Boolean,
    artworkOnly: Boolean,
    noScreenshots: Boolean,
    screenScraperKeyMissing: Boolean,
) {
    val metadata = settings.metadata

    ActionRow(
        title = "Download metadata",
        subtitle = when (scrapeState) {
            is ScrapeState.Running ->
                "${scrapeState.done} of ${scrapeState.total} — ${scrapeState.currentTitle}"

            is ScrapeState.Completed ->
                "Updated ${scrapeState.updated}, skipped ${scrapeState.skipped}"

            is ScrapeState.Failed -> scrapeState.message
            ScrapeState.NotConfigured -> "Add credentials below before scraping"
            ScrapeState.Idle -> "Fetch artwork and details for your games"
        },
        focused = focusedRow == 0,
        trailingLabel = if (scrapeState is ScrapeState.Running) "Cancel" else "Start",
        onClick = {
            if (scrapeState is ScrapeState.Running) {
                viewModel.cancelScrape()
            } else {
                viewModel.scrapeMetadata(onlyMissing = metadata.scrapeOnlyMissing)
            }
        },
    )
    RowDivider()
    SwitchRow(
        title = "Only fill in missing data",
        subtitle = "Leave already-scraped entries alone",
        checked = metadata.scrapeOnlyMissing,
        focused = focusedRow == 1,
        onCheckedChange = { on -> viewModel.updateMetadata { it.copy(scrapeOnlyMissing = on) } },
    )
    RowDivider()
    SwitchRow(
        title = "Ask during a full scrape",
        subtitle = "Stops on every game the providers disagree about. Off by " +
            "default so a library scrape can be left running — scraping a single " +
            "system always asks, whatever this is set to.",
        checked = metadata.askForMatches,
        focused = focusedRow == 2,
        onCheckedChange = { on -> viewModel.updateMetadata { it.copy(askForMatches = on) } },
    )
    RowDivider()
    /*
     * Trailers, for libraries scraped before THOR could fetch them.
     *
     * Its own action rather than a full re-scrape: "only missing" means *never
     * scraped*, so every existing game is skipped and no trailer ever arrives —
     * but re-scraping everything is hundreds of rate-limited calls to fill one
     * field most of them will not have. This asks only about games without one.
     */
    ActionRow(
        title = "Fetch missing trailers",
        subtitle = "Look up trailers for games that have none, without re-scraping " +
            "everything else",
        focused = focusedRow == 3,
        trailingLabel = "Fetch",
        onClick = viewModel::refreshTrailers,
    )

    RowDivider()
    ActionRow(
        title = "Check connections",
        subtitle = "Verify each provider's credentials actually work",
        focused = focusedRow == 4,
        trailingLabel = if (checking) "Checking…" else "Check",
        onClick = viewModel::checkProviderConnections,
    )

    // Artwork arriving while every text field stays blank looks like a broken
    // scraper. It is usually just SteamGridDB being the only enabled provider,
    // and SteamGridDB serves artwork only — so say which providers supply text.
    if (artworkOnly) {
        RowDivider()
        InfoRow(
            "No description source",
            "Turn ScreenScraper on for titles, credits and synopses, or Wikidata " +
                "for Wikipedia descriptions with no account needed.",
        )
    }

    // The same shape of fault one layer along. SteamGridDB fills every cover, so
    // the scrape plainly worked — but it holds nothing landscape, so the panel
    // has no image to show and nothing anywhere says why.
    if (noScreenshots) {
        RowDivider()
        InfoRow(
            "No screenshot source",
            "SteamGridDB has covers, banners and logos but no widescreen images. " +
                "ScreenScraper is the source of them — turn it on below, and the " +
                "game panel has something to show.",
        )
    }

    PROVIDERS.forEachIndexed { index, provider ->
        RowDivider()
        SwitchRow(
            title = provider.second,
            subtitle = providerStatus[provider.first].describe(),
            checked = provider.first in metadata.enabledProviders,
            focused = focusedRow == PROVIDER_FIRST_ROW + index,
            onCheckedChange = { on ->
                viewModel.updateMetadata { current ->
                    current.copy(
                        enabledProviders = if (on) {
                            current.enabledProviders + provider.first
                        } else {
                            current.enabledProviders - provider.first
                        },
                    )
                }
            },
        )
    }

    RowDivider()
    TextFieldRow(
        title = "SteamGridDB key",
        subtitle = "Artwork. From steamgriddb.com/profile/preferences/api",
        value = metadata.apiKeys[PROVIDER_STEAMGRIDDB].orEmpty(),
        placeholder = "API key",
        isSecret = true,
        focused = focusedRow == PROVIDER_FIRST_ROW + PROVIDERS.size,
        onValueChange = { viewModel.setApiKey(PROVIDER_STEAMGRIDDB, it) },
    )
    RowDivider()
    TextFieldRow(
        title = "ScreenScraper account",
        // These fields are what turns the provider on in a build with no
        // developer key of its own, which is this one.
        subtitle = if (screenScraperKeyMissing) {
            "Signs this launcher in to ScreenScraper. From screenscraper.fr"
        } else {
            "Optional — raises the daily quota and image quality"
        },
        value = metadata.screenScraperUser,
        placeholder = "Username",
        focused = focusedRow == PROVIDER_FIRST_ROW + PROVIDERS.size + 1,
        onValueChange = viewModel::setScreenScraperUser,
    )
    RowDivider()
    TextFieldRow(
        title = "ScreenScraper password",
        value = metadata.screenScraperPassword,
        placeholder = "Password",
        isSecret = true,
        focused = focusedRow == PROVIDER_FIRST_ROW + PROVIDERS.size + 2,
        onValueChange = viewModel::setScreenScraperPassword,
    )
}

/**
 * Where the provider switches start on the Metadata page.
 *
 * Named, and the credential rows below are counted from it, because the fixed
 * rows above have been renumbered by hand twice now — and every time, the rows
 * after them silently stopped matching the cursor. An index expressed as
 * arithmetic cannot drift from the list it is indexing.
 */
internal const val PROVIDER_FIRST_ROW = 5

private const val PROVIDER_STEAMGRIDDB = "steamgriddb"

/**
 * The sources a scrape can consult, in the order they are asked.
 *
 * Three, and each is here for something the other two cannot do. ScreenScraper
 * identifies a ROM by its hash and answers the whole question — title, credits,
 * date, genre, synopsis, artwork. SteamGridDB has the square grid image a cell
 * wants and nobody else holds one. Wikidata needs no account at all, so it is
 * what answers when nothing has been signed in to.
 *
 * Three sources were removed to get here. Each covered something ScreenScraper
 * could not while it was ranked below them, and every one of them cost a network
 * round trip per game per scrape — a source that agrees with the one above it is
 * time and quota spent to be ignored, and a switch for it is a decision put in
 * front of the reader with no answer behind it.
 */
internal val PROVIDERS = listOf(
    "screenscraper" to "ScreenScraper",
    PROVIDER_STEAMGRIDDB to "SteamGridDB",
    "wikidata" to "Wikidata",
)

/** Human-readable form of a provider probe result. */
private fun ProviderStatus?.describe(): String = when (this) {
    null, ProviderStatus.Unknown -> "Not checked"
    ProviderStatus.NotConfigured -> "No credentials"
    ProviderStatus.Connected -> "Connected"
    ProviderStatus.InvalidCredentials -> "Rejected — check the key"
    is ProviderStatus.Unreachable -> "Unreachable — $detail"
    is ProviderStatus.Error -> detail
}
