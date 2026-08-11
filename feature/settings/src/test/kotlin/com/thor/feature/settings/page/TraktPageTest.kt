package com.thor.feature.settings.page

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * How many rows the Trakt page has in each of its three states.
 *
 * The count clamps controller navigation, so it has to follow the same branch the
 * page itself took. This page is the one in Settings most likely to get that
 * wrong, because it changes shape *while the viewer is looking at it* — pressing
 * Connect replaces four rows with one, and the code appearing is not a
 * navigation.
 */
class TraktPageTest {

    @Test
    fun `signed out offers only the connect row`() {
        assertThat(traktRows(connected = false, signingIn = false)).isEqualTo(1)
    }

    /**
     * While the code is up, the only thing to press is Cancel — the switches
     * belong to an account that does not exist yet.
     */
    @Test
    fun `signing in offers only cancel`() {
        assertThat(traktRows(connected = false, signingIn = true)).isEqualTo(1)
    }

    @Test
    fun `a connected account offers both switches, a check and a sign out`() {
        assertThat(traktRows(connected = true, signingIn = false)).isEqualTo(4)
    }

    /**
     * Reconnecting while already signed in.
     *
     * Not a state the page offers a route to, but the count must still match what
     * is drawn: the page returns after the cancel row whenever a code is pending,
     * whatever the account says, so the count has to make the same choice or the
     * cursor lands on rows that are not there.
     */
    @Test
    fun `a pending code wins over being connected`() {
        assertThat(traktRows(connected = true, signingIn = true)).isEqualTo(1)
    }
}
