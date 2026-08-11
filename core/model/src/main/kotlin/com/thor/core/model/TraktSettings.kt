package com.thor.core.model

import kotlinx.serialization.Serializable

/**
 * The viewer's Trakt account, and what Loki does with it.
 *
 * Trakt is a record of what someone has watched that belongs to *them* rather
 * than to any one player. That is the whole reason to support it: the launcher's
 * own continue-watching shelf knows what was watched on this handheld, and stops
 * at the edge of it — start a series on a television and the handheld has no idea.
 * Trakt is the shared middle, so a shelf built from it is the same shelf
 * everywhere.
 *
 * Stored with the rest of the settings and encrypted at rest, as the debrid and
 * ScreenScraper credentials are. These are OAuth tokens rather than a password:
 * revoking them from trakt.tv is enough to cut this device off, without changing
 * anything else the account touches.
 */
@Serializable
data class TraktSettings(
    val accessToken: String = "",
    /**
     * Kept so the connection survives the access token expiring.
     *
     * Trakt's access tokens last three months, which sounds long enough not to
     * matter and is exactly why it does: a launcher that quietly stopped
     * scrobbling three months after being set up, with no error and nothing
     * asking to be signed in again, is the kind of failure nobody attributes to
     * the right cause.
     */
    val refreshToken: String = "",
    /** When [accessToken] stops working, so it is renewed before it is used. */
    val expiresAtEpochMs: Long = 0L,
    /** Shown on the settings page so the account is identifiable, not just "connected". */
    val username: String = "",
    /**
     * Reports what is being watched, as it is watched.
     *
     * On by default once an account is connected, because it is the reason to
     * connect one. A viewer who wants the shelves without the reporting turns it
     * off here rather than having to choose between both and neither.
     */
    val scrobble: Boolean = true,
    /** Adds watchlist and Trakt's own resume shelf to the browse rows. */
    val showRows: Boolean = true,
) {
    val isConnected: Boolean get() = accessToken.isNotBlank()

    /**
     * Whether the token needs renewing before the next call.
     *
     * Renewed early rather than on failure. A token that expires mid-scrobble
     * costs a silently dropped play, and the refresh is one request against a
     * three-month window — there is no reason to wait for the failure to find
     * out.
     */
    fun needsRefresh(nowEpochMs: Long): Boolean =
        isConnected && refreshToken.isNotBlank() && nowEpochMs >= expiresAtEpochMs - REFRESH_MARGIN_MS

    private companion object {
        /** A day's grace, so a device left off for a while still renews rather than fails. */
        const val REFRESH_MARGIN_MS = 24L * 60L * 60L * 1000L
    }
}
