package com.thor.feature.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import com.thor.core.model.ThorSettings
import com.thor.data.media.TraktDeviceCode
import com.thor.feature.settings.SettingsViewModel
import com.thor.feature.settings.component.RowDivider
import com.thor.feature.settings.component.row.ActionRow
import com.thor.feature.settings.component.row.InfoRow
import com.thor.feature.settings.component.row.SwitchRow

/**
 * The viewer's Trakt account.
 *
 * Trakt is a record of what someone has watched that belongs to them rather than
 * to any one player, which is what makes it worth having here: the launcher's own
 * continue-watching shelf stops at the edge of this device, and an evening that
 * started on a television is invisible to it. Connected, the shelves are the same
 * everywhere.
 *
 * ## Signing in without typing
 *
 * A code, shown here, entered on whatever device the viewer already has in their
 * hand. The ordinary OAuth dance wants a browser, an address bar and a redirect,
 * and a handheld has none of those — its text entry is a pad-driven keyboard, so
 * asking someone to type an email address and a password on it is asking a lot
 * for something they will get wrong twice. Nothing sensitive is typed on this
 * device at all; the code is worthless to anyone who does not also hold the
 * account.
 */
@Composable
internal fun TraktPage(
    settings: ThorSettings,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    status: String?,
    /** The code being waited on, or null when not signing in. */
    pendingCode: TraktDeviceCode?,
) {
    val trakt = settings.media.trakt

    InfoRow(
        "Trakt",
        status ?: when {
            pendingCode != null -> "Waiting for the code to be entered…"
            trakt.isConnected -> "Signed in as ${trakt.username.ifBlank { "your account" }}"
            else -> "Keeps track of what you watch, across every device you watch " +
                "on. Your watchlist and what you are part-way through appear in " +
                "Films & shows."
        },
    )
    RowDivider()

    if (pendingCode != null) {
        /*
         * The code, and where to put it.
         *
         * Spelled out in full rather than shortened to "go to trakt.tv" — the
         * activate page is not somewhere people know the address of, and a
         * viewer who guesses at it lands on a sign-in page that does nothing
         * with the code they are holding.
         */
        InfoRow("Your code", pendingCode.userCode)
        RowDivider()
        InfoRow("Enter it at", pendingCode.verificationUrl)
        RowDivider()
        ActionRow(
            title = "Cancel signing in",
            icon = Icons.Rounded.Close,
            subtitle = "Stops waiting. Nothing is connected.",
            focused = focusedRow == 0,
            trailingLabel = "Cancel",
            onClick = viewModel::cancelTraktSignIn,
        )
        return
    }

    if (!trakt.isConnected) {
        ActionRow(
            title = "Connect an account",
            icon = Icons.Rounded.Login,
            subtitle = "Shows a short code to enter on your phone or computer. " +
                "Nothing is typed on this device.",
            focused = focusedRow == 0,
            trailingLabel = "Connect",
            onClick = viewModel::connectTrakt,
        )
        return
    }

    SwitchRow(
        title = "Report what I watch",
        icon = Icons.Rounded.Visibility,
        subtitle = "Marks films and episodes as watched on Trakt as you play them, " +
            "and picks up where you left off on another device.",
        checked = trakt.scrobble,
        focused = focusedRow == 0,
        onCheckedChange = viewModel::setTraktScrobble,
    )
    RowDivider()
    SwitchRow(
        title = "Show my Trakt shelves",
        icon = Icons.Rounded.ViewCarousel,
        subtitle = "Adds your watchlist and what you are part-way through to " +
            "Films & shows, above the catalogue.",
        checked = trakt.showRows,
        focused = focusedRow == 1,
        onCheckedChange = viewModel::setTraktRows,
    )
    RowDivider()
    ActionRow(
        title = "Check the connection",
        icon = Icons.Rounded.NetworkCheck,
        subtitle = "Asks Trakt who this device is signed in as",
        focused = focusedRow == 2,
        trailingLabel = "Check",
        onClick = viewModel::checkTrakt,
    )
    RowDivider()
    ActionRow(
        title = "Sign out",
        icon = Icons.Rounded.Logout,
        // Worth saying: signing out of a *tracking* service sounds like it might
        // take the history with it, and it does not. Nothing here has ever
        // written to the account except plays the viewer asked to be recorded.
        subtitle = "Forgets the account on this device. Your Trakt history and " +
            "watchlist are untouched.",
        focused = focusedRow == 3,
        trailingLabel = "Sign out",
        destructive = true,
        onClick = viewModel::disconnectTrakt,
    )
}

/**
 * Row counts, which differ per state rather than per setting.
 *
 * Three shapes: signing in, signed out and connected. The count is what clamps
 * controller navigation, so it has to follow the same branch the page took —
 * counting the connected page's four rows while the sign-in code is on screen
 * strands the cursor on rows that are not drawn.
 */
internal fun traktRows(connected: Boolean, signingIn: Boolean): Int = when {
    signingIn -> 1
    connected -> 4
    else -> 1
}
