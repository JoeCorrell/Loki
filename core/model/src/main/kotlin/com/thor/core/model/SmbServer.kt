package com.thor.core.model

import kotlinx.serialization.Serializable

/**
 * One network share the file explorer can browse.
 *
 * Stored in [ThorSettings] rather than in a keystore of its own. The password is
 * a field like any other and is encrypted at rest with everything else in the
 * settings document, which is the same protection the ScreenScraper and debrid
 * credentials already have. The alternative — Android's hardware keystore — is
 * stronger against an attacker holding an unlocked device, and the cost is that
 * the key never leaves the hardware: a backup, a restore or a move to a new
 * device would bring every server across with its password silently blank. For a
 * NAS on a home network that trade is the wrong way round.
 *
 * A share is *not* part of the record. `smb://tower/` lists the shares the server
 * offers, and browsing to the one you want is a keypress rather than a field to
 * fill in correctly — which also means a server exporting five shares is one
 * entry here instead of five.
 */
@Serializable
data class SmbServer(
    /** Stable across edits, so the shortcut rail and the settings list agree. */
    val id: String,
    /**
     * What the rail calls it. Blank falls back to [host]; see [displayName].
     *
     * Worth having because a host is often an address rather than a name, and a
     * rail entry reading `192.168.1.42` tells you nothing about which machine
     * that is.
     */
    val label: String = "",
    /** Hostname or IP. NetBIOS names are not resolved; see the note in `SmbFileSource`. */
    val host: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * The Windows domain or workgroup, when there is one.
     *
     * Almost always blank on a home network — a NAS authenticates against its own
     * user list, not against a domain controller — so it is last and defaulted.
     */
    val domain: String = "",
    /**
     * Connects with no credentials at all.
     *
     * Its own flag rather than inferred from an empty username, because the two
     * are different requests: jcifs sends anonymous credentials for one and the
     * empty-password guest account for the other, and a server can accept either
     * while refusing the other.
     */
    val guest: Boolean = false,
    /**
     * Opens straight into this share instead of the server's list of them.
     *
     * Optional, and the reason it exists is that some servers refuse to enumerate
     * their shares while happily serving one you name — the enumeration needs
     * rights on IPC$ that a locked-down NAS often withholds from ordinary
     * accounts. Without this those servers look empty rather than reachable.
     */
    val share: String = "",
) {
    val displayName: String get() = label.ifBlank { host }.ifBlank { "Network share" }

    /**
     * Where browsing this server starts.
     *
     * A trailing slash on both forms, which jcifs requires of anything it is to
     * treat as a directory — without it, `smb://tower/games` is a *file* called
     * `games` in the root of the server and listing it throws.
     */
    val rootPath: String
        get() = if (share.isBlank()) {
            "$SMB_SCHEME$host/"
        } else {
            "$SMB_SCHEME$host/${share.trim('/')}/"
        }

    /** Enough to attempt a connection. Credentials may legitimately be empty. */
    val isUsable: Boolean get() = host.isNotBlank()
}

/** The prefix that tells the explorer a path is not on this device. */
const val SMB_SCHEME = "smb://"

/**
 * Whether [path] names something on a network share rather than on the device.
 *
 * A scheme test rather than a lookup, so it can be asked from anywhere — the
 * breadcrumb builder, the shortcut rail and the repository all need the answer
 * and none of them should have to hold a list of servers to get it.
 */
fun isRemotePath(path: String): Boolean = path.startsWith(SMB_SCHEME, ignoreCase = true)
