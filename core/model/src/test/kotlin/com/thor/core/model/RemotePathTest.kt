package com.thor.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Paths that are not on this device.
 *
 * String arithmetic, and worth testing precisely because it looks too simple to
 * get wrong. The explorer keys its mark set on the comparison in [parentPathOf],
 * so one slash out of place does not throw — it silently reports files that are
 * in plain view as being somewhere else, and every action that says "3 elsewhere"
 * starts lying about what it is going to touch.
 */
class RemotePathTest {

    // ---- Telling them apart -------------------------------------------------

    @Test
    fun `an smb url is remote and a device path is not`() {
        assertThat(isRemotePath("smb://tower/games/")).isTrue()
        assertThat(isRemotePath("/storage/emulated/0/ROMs")).isFalse()
        assertThat(isRemotePath("/storage/smb://not-really")).isFalse()
    }

    /** Schemes are case-insensitive, and something will eventually paste one. */
    @Test
    fun `the scheme is matched whatever its case`() {
        assertThat(isRemotePath("SMB://tower/")).isTrue()
    }

    // ---- Parents ------------------------------------------------------------

    @Test
    fun `a remote file's parent keeps the trailing slash`() {
        assertThat(parentPathOf("smb://tower/games/snes/rom.sfc"))
            .isEqualTo("smb://tower/games/snes/")
    }

    /**
     * The comparison the mark set actually makes.
     *
     * A directory is written with a trailing slash everywhere in the explorer, so
     * the parent of a file inside it has to come back spelled the same way or
     * nothing ever matches.
     */
    @Test
    fun `a marked file's parent equals the folder it is being listed in`() {
        val folder = "smb://tower/games/snes/"
        assertThat(parentPathOf("smb://tower/games/snes/rom.sfc")).isEqualTo(folder)
    }

    @Test
    fun `a directory's parent is the folder above it`() {
        assertThat(parentPathOf("smb://tower/games/snes/")).isEqualTo("smb://tower/games/")
        assertThat(parentPathOf("smb://tower/games/")).isEqualTo("smb://tower/")
    }

    /** The server is the top; there is nothing above it worth navigating to. */
    @Test
    fun `a server has no parent`() {
        assertThat(parentPathOf("smb://tower/")).isEmpty()
        assertThat(parentPathOf("smb://tower")).isEmpty()
    }

    @Test
    fun `a local path's parent is unchanged`() {
        assertThat(parentPathOf("/storage/emulated/0/ROMs/game.sfc"))
            .isEqualTo("/storage/emulated/0/ROMs")
    }

    // ---- Names --------------------------------------------------------------

    @Test
    fun `a name is the last segment either way`() {
        assertThat(fileNameOf("smb://tower/games/snes/rom.sfc")).isEqualTo("rom.sfc")
        assertThat(fileNameOf("smb://tower/games/snes/")).isEqualTo("snes")
        assertThat(fileNameOf("/storage/emulated/0/ROMs")).isEqualTo("ROMs")
    }

    // ---- Building paths -----------------------------------------------------

    @Test
    fun `a child does not gain a double slash`() {
        assertThat(childPathOf("smb://tower/games/", "snes")).isEqualTo("smb://tower/games/snes")
        assertThat(childPathOf("/storage/ROMs", "snes")).isEqualTo("/storage/ROMs/snes")
    }

    @Test
    fun `a rename produces a neighbour rather than a child`() {
        assertThat(siblingPath("smb://tower/games/old.sfc", "new.sfc"))
            .isEqualTo("smb://tower/games/new.sfc")
        assertThat(siblingPath("/storage/ROMs/old.sfc", "new.sfc"))
            .isEqualTo("/storage/ROMs/new.sfc")
    }

    // ---- Breadcrumbs --------------------------------------------------------

    /**
     * The generic builder splits on `/` and would produce a crumb for `smb:` and
     * one for nothing at all, both of them dead.
     */
    @Test
    fun `a share's crumbs are the server, the share and the folders`() {
        val crumbs = breadcrumbs("smb://tower/games/snes/")

        assertThat(crumbs.map(Breadcrumb::label))
            .containsExactly("tower", "games", "snes")
            .inOrder()
    }

    /** Every crumb has to be navigable, which for jcifs means a trailing slash. */
    @Test
    fun `each crumb is a directory path in its own right`() {
        val crumbs = breadcrumbs("smb://tower/games/snes/")

        assertThat(crumbs.map(Breadcrumb::path)).containsExactly(
            "smb://tower/",
            "smb://tower/games/",
            "smb://tower/games/snes/",
        ).inOrder()
    }

    @Test
    fun `a local path still gets its storage root`() {
        val crumbs = breadcrumbs("/storage/emulated/0")

        assertThat(crumbs.first().label).isEqualTo("Storage")
        assertThat(crumbs.first().path).isEqualTo("/")
    }

    // ---- The server record --------------------------------------------------

    @Test
    fun `a server with no share opens on its list of shares`() {
        val server = SmbServer(id = "1", host = "tower")
        assertThat(server.rootPath).isEqualTo("smb://tower/")
    }

    /** Named, it skips the enumeration a locked-down NAS may refuse. */
    @Test
    fun `a named share opens straight into it`() {
        val server = SmbServer(id = "1", host = "tower", share = "games")
        assertThat(server.rootPath).isEqualTo("smb://tower/games/")
    }

    /** A user typing `/games/` must not produce a path with a doubled slash. */
    @Test
    fun `slashes typed around a share name are ignored`() {
        val server = SmbServer(id = "1", host = "tower", share = "/games/")
        assertThat(server.rootPath).isEqualTo("smb://tower/games/")
    }

    @Test
    fun `a server falls back to its address when it has no name`() {
        assertThat(SmbServer(id = "1", host = "192.168.1.20").displayName)
            .isEqualTo("192.168.1.20")
        assertThat(SmbServer(id = "1", host = "192.168.1.20", label = "Tower").displayName)
            .isEqualTo("Tower")
    }

    @Test
    fun `a server with no address cannot be used`() {
        assertThat(SmbServer(id = "1").isUsable).isFalse()
        assertThat(SmbServer(id = "1", host = "tower").isUsable).isTrue()
    }
}
