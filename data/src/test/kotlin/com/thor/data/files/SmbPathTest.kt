package com.thor.data.files

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.SmbServer
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The SMB source's path arithmetic, with no server anywhere near it.
 *
 * Everything here is pure string work that runs before a single packet is sent,
 * and all of it is the sort that fails quietly: a directory URL missing its
 * trailing slash does not throw, it makes jcifs treat a folder as a file and
 * report that the path "is not a directory" about something that plainly is.
 *
 * What is deliberately not here is anything that talks to a server. Those paths
 * need a real one, and a fake SMB server in a unit test would be testing the
 * fake.
 */
class SmbPathTest {

    private val source = SmbFileSource {
        listOf(
            SmbServer(id = "a", label = "Tower", host = "tower", username = "joe"),
            SmbServer(id = "b", host = "192.168.1.20", guest = true),
            // No address, so it is not somewhere anyone can be sent.
            SmbServer(id = "c", label = "Half finished"),
        )
    }

    // ---- Ownership ----------------------------------------------------------

    @Test
    fun `only smb paths are claimed`() {
        assertThat(source.handles("smb://tower/games/")).isTrue()
        assertThat(source.handles("/storage/emulated/0/ROMs")).isFalse()
    }

    // ---- The rail -----------------------------------------------------------

    @Test
    fun `every usable server becomes a shortcut, marked as remote`() = runTest {
        val shortcuts = source.shortcuts()

        assertThat(shortcuts.map(FileShortcut::label))
            .containsExactly("Tower", "192.168.1.20")
        assertThat(shortcuts.all(FileShortcut::remote)).isTrue()
    }

    /**
     * A server with no address is left out of the rail.
     *
     * It is kept in settings — it is half-typed, not deleted — but a rail entry
     * that cannot go anywhere is a dead end the user discovers by pressing it.
     */
    @Test
    fun `a server with no address is not offered`() = runTest {
        assertThat(source.shortcuts().map(FileShortcut::label)).doesNotContain("Half finished")
    }

    @Test
    fun `a shortcut opens on the server's own root`() = runTest {
        assertThat(source.shortcuts().first().path).isEqualTo("smb://tower/")
    }

    // ---- Walking up ---------------------------------------------------------

    @Test
    fun `a folder's parent keeps the trailing slash jcifs needs`() {
        assertThat(source.parentOf("smb://tower/games/snes/")).isEqualTo("smb://tower/games/")
        assertThat(source.parentOf("smb://tower/games/rom.sfc")).isEqualTo("smb://tower/games/")
    }

    @Test
    fun `a share's parent is the server's list of shares`() {
        assertThat(source.parentOf("smb://tower/games/")).isEqualTo("smb://tower/")
    }

    /**
     * Null at the server, which is what makes Back leave the remote tree.
     *
     * Returning `smb://` instead would navigate to a scheme with no listing behind
     * it — a screen that can only say the folder is not there.
     */
    @Test
    fun `a server is the top of the tree`() {
        assertThat(source.parentOf("smb://tower/")).isNull()
        assertThat(source.parentOf("smb://tower")).isNull()
    }

    // ---- Walking down -------------------------------------------------------

    @Test
    fun `a child is joined with exactly one slash`() {
        assertThat(source.childPath("smb://tower/games/", "snes"))
            .isEqualTo("smb://tower/games/snes")
        assertThat(source.childPath("smb://tower/games", "snes"))
            .isEqualTo("smb://tower/games/snes")
    }

    @Test
    fun `a name is the last segment, with or without a trailing slash`() {
        assertThat(source.nameOf("smb://tower/games/snes/")).isEqualTo("snes")
        assertThat(source.nameOf("smb://tower/games/rom.sfc")).isEqualTo("rom.sfc")
    }

    // ---- Directory URLs -----------------------------------------------------

    @Test
    fun `a directory url gains a slash only when it needs one`() {
        assertThat(directoryUrl("smb://tower/games")).isEqualTo("smb://tower/games/")
        assertThat(directoryUrl("smb://tower/games/")).isEqualTo("smb://tower/games/")
    }

    // ---- Identity -----------------------------------------------------------

    /**
     * The same-file guard compares these, and a folder reaches it written both
     * ways — with the slash from a listing, without it from a path that was built.
     */
    @Test
    fun `a folder has one identity however it is spelled`() = runTest {
        assertThat(source.identityOf("smb://tower/games/"))
            .isEqualTo(source.identityOf("smb://tower/games"))
    }
}
