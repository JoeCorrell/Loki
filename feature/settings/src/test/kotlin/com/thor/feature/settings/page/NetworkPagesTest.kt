package com.thor.feature.settings.page

import com.google.common.truth.Truth.assertThat
import com.thor.data.files.DiscoveredServer
import org.junit.Test

/**
 * How many rows the network shares page has.
 *
 * Arithmetic, and the one kind of mistake on a settings page that has no visible
 * symptom: the count is what clamps controller navigation, so an overshoot is a
 * press that highlights nothing and an undershoot is a row nobody can reach. The
 * scan makes this page the only one whose length changes while the user is
 * looking at it, which is exactly when a wrong count is noticed.
 */
class NetworkPagesTest {

    @Test
    fun `an empty page still has the scan and the manual add`() {
        assertThat(networkSharesRows(serverCount = 0, discoveredCount = 0, editing = false))
            .isEqualTo(2)
    }

    @Test
    fun `configured servers each add a row`() {
        assertThat(networkSharesRows(serverCount = 3, discoveredCount = 0, editing = false))
            .isEqualTo(5)
    }

    /** The case the scan creates: rows appear underneath while the page is open. */
    @Test
    fun `discovered servers add rows of their own`() {
        assertThat(networkSharesRows(serverCount = 1, discoveredCount = 4, editing = false))
            .isEqualTo(7)
    }

    /**
     * Opening a server replaces the list rather than extending it, so the count
     * must not carry the list's rows with it.
     */
    @Test
    fun `an open server is a fixed page whatever the list held`() {
        assertThat(networkSharesRows(serverCount = 9, discoveredCount = 9, editing = true))
            .isEqualTo(SMB_SERVER_EDIT_ROWS)
    }

    // ---- What a found server says about itself -----------------------------

    /**
     * A sweep hit has no name, and the row still has to say something useful —
     * an address on both lines would be the same fact twice.
     */
    @Test
    fun `an unnamed server shows its address and says how it was found`() {
        val found = DiscoveredServer(address = "192.168.1.20")

        assertThat(found.displayName).isEqualTo("192.168.1.20")
        assertThat(found.detail).isEqualTo("Answered on port 445")
    }

    @Test
    fun `a named server shows the name over the address`() {
        val found = DiscoveredServer(address = "192.168.1.20", name = "Tower")

        assertThat(found.displayName).isEqualTo("Tower")
        assertThat(found.detail).isEqualTo("192.168.1.20")
    }
}
