package com.thor.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The three debrid services, and which credential each one uses.
 *
 * Small, and worth having because the credential is read by service: a token
 * saved under the wrong key is a working account that reports itself as not set
 * up, which sends the user to re-copy a token that was fine all along.
 */
class DebridServiceSelectionTest {

    @Test
    fun `each service reads its own credential`() {
        val settings = MediaSettings(
            realDebridToken = "rd",
            torBoxApiKey = "tb",
            allDebridApiKey = "ad",
        )

        assertThat(settings.copy(debridService = DebridService.REAL_DEBRID).debridToken)
            .isEqualTo("rd")
        assertThat(settings.copy(debridService = DebridService.TORBOX).debridToken)
            .isEqualTo("tb")
        assertThat(settings.copy(debridService = DebridService.ALL_DEBRID).debridToken)
            .isEqualTo("ad")
    }

    /** Switching service must not lose the other tokens; see the note on the field. */
    @Test
    fun `switching service keeps the credentials that are not in use`() {
        val settings = MediaSettings(
            realDebridToken = "rd",
            torBoxApiKey = "tb",
            allDebridApiKey = "ad",
            debridService = DebridService.ALL_DEBRID,
        )

        assertThat(settings.realDebridToken).isEqualTo("rd")
        assertThat(settings.torBoxApiKey).isEqualTo("tb")
    }

    @Test
    fun `a service with no credential is not configured`() {
        val settings = MediaSettings(
            realDebridToken = "rd",
            debridService = DebridService.ALL_DEBRID,
        )

        assertThat(settings.isDebridConfigured).isFalse()
    }

    /**
     * AllDebrid withdrew its instant-availability endpoint and nothing replaced
     * it, so the "only instantly playable sources" filter cannot work with that
     * account selected. The settings row reads this to say so rather than
     * appearing to ignore a switch.
     */
    @Test
    fun `only AllDebrid declines to report what it has cached`() {
        assertThat(DebridService.REAL_DEBRID.reportsCachedFiles).isTrue()
        assertThat(DebridService.TORBOX.reportsCachedFiles).isTrue()
        assertThat(DebridService.ALL_DEBRID.reportsCachedFiles).isFalse()
    }

    /** Every service names its credential the way its own website does. */
    @Test
    fun `every service labels its credential`() {
        DebridService.entries.forEach { service ->
            assertThat(service.credentialLabel).isNotEmpty()
            assertThat(service.label).isNotEmpty()
        }
    }
}
