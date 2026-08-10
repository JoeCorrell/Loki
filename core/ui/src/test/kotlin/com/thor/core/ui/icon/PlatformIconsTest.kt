package com.thor.core.ui.icon

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.PlatformArtwork
import org.junit.Test

/**
 * When the artwork Loki ships stands aside.
 *
 * The rule is one line and got the question wrong by one word, which cost every
 * platform its icon the moment a backdrop was chosen for it. Worth a test because
 * the fault is invisible at the call site: the icon simply stops being drawn, and
 * the code that caused it is in a different file about a different slot.
 */
class PlatformIconsTest {

    @Test
    fun `a platform with no artwork of its own gets the bundled icon`() {
        assertThat(PlatformIcons.preferredOver(PlatformArtwork.NONE, "snes")).isNotNull()
        assertThat(PlatformIcons.preferredOver(null, "snes")).isNotNull()
    }

    @Test
    fun `an icon from a pack replaces it`() {
        val fromPack = PlatformArtwork(iconUri = "content://icon.png", packId = "some-pack")

        assertThat(PlatformIcons.preferredOver(fromPack, "snes")).isNull()
    }

    /**
     * The regression: choosing a *backdrop* used to delete the icon.
     *
     * `setPlatformArtwork` stamps the ownership marker across the whole record
     * whichever slot was filled, so a hero-only choice claimed the icon slot it
     * had never written to.
     */
    @Test
    fun `choosing a backdrop leaves the bundled icon alone`() {
        val heroOnly = PlatformArtwork(
            heroUri = "content://backdrop.jpg",
            packId = PlatformArtwork.USER_PACK_ID,
        )

        assertThat(PlatformIcons.preferredOver(heroOnly, "snes")).isNotNull()
    }

    /** Nor does a wordmark, for the same reason: it is not a square icon either. */
    @Test
    fun `a logo does not displace it`() {
        val logoOnly = PlatformArtwork(logoUri = "content://wordmark.png", packId = "some-pack")

        assertThat(PlatformIcons.preferredOver(logoOnly, "snes")).isNotNull()
    }

    /** An owner with nothing behind it owns nothing. */
    @Test
    fun `a marker with no icon behind it displaces nothing`() {
        val bare = PlatformArtwork(packId = PlatformArtwork.USER_PACK_ID)

        assertThat(PlatformIcons.preferredOver(bare, "snes")).isNotNull()
    }

    @Test
    fun `a system with no bundled artwork has none to offer`() {
        assertThat(PlatformIcons.preferredOver(PlatformArtwork.NONE, "not-a-console")).isNull()
        assertThat(PlatformIcons.preferredOver(PlatformArtwork.NONE, null)).isNull()
    }
}
