package com.thor.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When a Trakt token is renewed.
 *
 * Worth testing because the failure is invisible and slow. Trakt's access tokens
 * last three months, so a launcher that renews at the wrong moment does not break
 * on the day it is set up — it quietly stops scrobbling a quarter of a year
 * later, with no error and nothing asking to be signed in again, which is the
 * kind of fault nobody attributes to the right cause.
 */
class TraktSettingsTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    private fun connected(expiresAt: Long) = TraktSettings(
        accessToken = "access",
        refreshToken = "refresh",
        expiresAtEpochMs = expiresAt,
    )

    @Test
    fun `a token with months left is left alone`() {
        assertThat(connected(now + 90 * day).needsRefresh(now)).isFalse()
    }

    /**
     * A day's grace, so the renewal happens before the token is needed rather
     * than after a call has already failed with it.
     */
    @Test
    fun `a token expiring within a day is renewed early`() {
        assertThat(connected(now + day / 2).needsRefresh(now)).isTrue()
    }

    @Test
    fun `a token that has already expired is renewed`() {
        assertThat(connected(now - day).needsRefresh(now)).isTrue()
    }

    // ---- Nothing to renew ---------------------------------------------------

    /** No account: there is nothing to refresh and nothing to report. */
    @Test
    fun `a disconnected account never asks for a refresh`() {
        assertThat(TraktSettings().needsRefresh(now)).isFalse()
    }

    /**
     * An access token with no refresh token beside it cannot be renewed at all,
     * and asking would spend a request to be told so.
     */
    @Test
    fun `an account with no refresh token does not try`() {
        val stranded = TraktSettings(accessToken = "access", expiresAtEpochMs = now - day)

        assertThat(stranded.needsRefresh(now)).isFalse()
    }

    @Test
    fun `an account is connected exactly when it holds an access token`() {
        assertThat(TraktSettings().isConnected).isFalse()
        assertThat(TraktSettings(accessToken = "access").isConnected).isTrue()
    }

    /**
     * Scrobbling and the shelves are on once an account is connected.
     *
     * Reporting what you watch is the reason to connect one, so defaulting it off
     * would mean every viewer having to find a switch to get the thing they just
     * signed in for.
     */
    @Test
    fun `a new account reports and shows its shelves`() {
        assertThat(TraktSettings().scrobble).isTrue()
        assertThat(TraktSettings().showRows).isTrue()
    }
}
