package com.thor.feature.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CorporateFare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.thor.core.model.SmbServer
import com.thor.data.files.DiscoveredServer
import com.thor.data.files.SmbDiscovery
import com.thor.feature.settings.SettingsViewModel
import com.thor.feature.settings.component.RowDivider
import com.thor.feature.settings.component.row.ActionRow
import com.thor.feature.settings.component.row.InfoRow
import com.thor.feature.settings.component.row.SwitchRow
import com.thor.feature.settings.component.row.TextFieldRow

/**
 * Windows and NAS shares the file explorer can browse.
 *
 * Two states, as the smart folder and theme editors have and for the same reason:
 * closed, it is a short list of what you have set up and a way to add another;
 * open, it is one server's fields. Nobody thinks of "my NAS" and "editing my NAS"
 * as two places to navigate between.
 *
 * Scanning leads, and typing an address is kept beside it rather than replaced by
 * it. No single discovery method reaches every network — see [SmbDiscovery] for
 * what the scan actually does — so a browse button on its own would leave anyone
 * whose network drops multicast, or whose server sits on another subnet, with a
 * screen that says nothing is there and no way to disagree with it.
 */
@Composable
internal fun NetworkSharesPage(
    servers: List<SmbServer>,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    /** Which server is open, from [SettingsViewModel.editingSmbServerId]. */
    editingId: String?,
    status: String?,
    testing: Boolean,
    discovered: List<DiscoveredServer>,
    scanning: Boolean,
) {
    val editing = servers.firstOrNull { it.id == editingId }
    if (editing == null) {
        ServerList(servers, discovered, scanning, focusedRow, viewModel, status)
    } else {
        ServerFields(editing, focusedRow, viewModel, status, testing)
    }
}

@Composable
private fun ServerList(
    servers: List<SmbServer>,
    discovered: List<DiscoveredServer>,
    scanning: Boolean,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    status: String?,
) {
    LaunchedEffect(viewModel) {
        viewModel.onNetworkSharesShown()
    }

    InfoRow(
        "Network shares",
        status ?: "Browse a NAS or a PC's shared folders from the file explorer, " +
            "and copy between them and this device. Shares appear in the " +
            "explorer's sidebar under your storage.",
    )
    RowDivider()

    ActionRow(
        title = "Nearby file servers",
        icon = Icons.Rounded.Radar,
        subtitle = "Scans automatically when this page opens. Refresh after joining " +
            "another network or waking a server.",
        focused = focusedRow == SCAN_ROW,
        trailingLabel = if (scanning) "Scanning…" else "Refresh",
        onClick = viewModel::scanForSmbServers,
    )
    RowDivider()
    ActionRow(
        title = "Add a server by address",
        icon = Icons.Rounded.Add,
        subtitle = "For a server the scan cannot reach — another subnet, or a " +
            "network that blocks discovery",
        focused = focusedRow == ADD_ROW,
        trailingLabel = "Add",
        onClick = viewModel::addSmbServer,
    )

    /*
     * What the scan found, above what is already set up.
     *
     * Above, because it is the shorter-lived list and the one the user is looking
     * at the moment it appears — a result that arrived under six configured
     * servers is a result nobody sees.
     */
    discovered.forEachIndexed { index, host ->
        RowDivider()
        ActionRow(
            title = host.displayName,
            subtitle = host.detail,
            focused = focusedRow == DISCOVERED_FIRST_ROW + index,
            trailingLabel = "Add",
            onClick = { viewModel.addDiscoveredServer(host) },
        )
    }

    if (servers.isEmpty()) return

    servers.forEachIndexed { index, server ->
        RowDivider()
        ActionRow(
            title = server.displayName,
            subtitle = describeServer(server),
            focused = focusedRow == DISCOVERED_FIRST_ROW + discovered.size + index,
            trailingLabel = "Edit",
            onClick = { viewModel.editSmbServer(server.id) },
        )
    }
}

@Composable
private fun ServerFields(
    server: SmbServer,
    focusedRow: Int,
    viewModel: SettingsViewModel,
    status: String?,
    testing: Boolean,
) {
    InfoRow(server.displayName, status ?: describeServer(server))
    RowDivider()

    TextFieldRow(
        title = "Address",
        icon = Icons.Rounded.Dns,
        subtitle = "The server's hostname or IP — \"tower\", \"nas.local\", " +
            "\"192.168.1.20\". Leave off the slashes.",
        value = server.host,
        placeholder = "192.168.1.20",
        focused = focusedRow == 0,
        onValueChange = { host ->
            viewModel.updateSmbServer(server.id) { it.copy(host = host.cleanHost()) }
        },
    )
    RowDivider()
    TextFieldRow(
        title = "Name",
        icon = Icons.Rounded.Badge,
        subtitle = "What the explorer's sidebar calls it. Blank uses the address.",
        value = server.label,
        placeholder = server.host.ifBlank { "Network share" },
        focused = focusedRow == 1,
        onValueChange = { label ->
            viewModel.updateSmbServer(server.id) { it.copy(label = label.trim()) }
        },
    )
    RowDivider()

    SwitchRow(
        title = "Connect as a guest",
        icon = Icons.Rounded.Person,
        subtitle = "For shares that are open to anyone. Turn this off to sign in.",
        checked = server.guest,
        focused = focusedRow == 2,
        onCheckedChange = { guest ->
            viewModel.updateSmbServer(server.id) { it.copy(guest = guest) }
        },
    )
    RowDivider()
    TextFieldRow(
        title = "Username",
        icon = Icons.Rounded.Person,
        subtitle = if (server.guest) {
            "Not used while guest access is on"
        } else {
            "The account on the server, not your Loki profile"
        },
        value = server.username,
        placeholder = "Anonymous",
        focused = focusedRow == 3,
        onValueChange = { user ->
            viewModel.updateSmbServer(server.id) { it.copy(username = user.trim()) }
        },
    )
    RowDivider()
    TextFieldRow(
        title = "Password",
        icon = Icons.Rounded.Password,
        // Said plainly rather than left to be assumed. It is the same protection
        // the ScreenScraper and debrid credentials already have, and somebody
        // typing a NAS password into a games launcher deserves to be told.
        subtitle = "Stored encrypted on this device, with the rest of your settings",
        value = server.password,
        isSecret = true,
        focused = focusedRow == 4,
        onValueChange = { password ->
            viewModel.updateSmbServer(server.id) { it.copy(password = password) }
        },
    )
    RowDivider()
    TextFieldRow(
        title = "Domain",
        icon = Icons.Rounded.CorporateFare,
        subtitle = "Only for a work network with a domain controller. Leave blank " +
            "for a NAS or a home PC.",
        value = server.domain,
        placeholder = "None",
        focused = focusedRow == 5,
        onValueChange = { domain ->
            viewModel.updateSmbServer(server.id) { it.copy(domain = domain.trim()) }
        },
    )
    RowDivider()
    TextFieldRow(
        title = "Share",
        icon = Icons.Rounded.FolderShared,
        /*
         * Optional, and the reason it is here is worth stating.
         *
         * Left blank the explorer opens the server itself and lists what it
         * offers, which is better — you pick from what is really there instead of
         * spelling a name correctly. But enumerating shares needs rights on IPC$
         * that a locked-down NAS often withholds from ordinary accounts, and such
         * a server looks empty rather than reachable. Naming one skips the
         * enumeration entirely.
         */
        subtitle = "Leave blank to see every share on the server. Fill it in only " +
            "if the list comes back empty.",
        value = server.share,
        placeholder = "All shares",
        focused = focusedRow == 6,
        onValueChange = { share ->
            viewModel.updateSmbServer(server.id) { it.copy(share = share.trim().trim('/')) }
        },
    )
    RowDivider()

    ActionRow(
        title = "Test the connection",
        icon = Icons.Rounded.NetworkCheck,
        subtitle = "Signs in and lists what is there. Nothing is changed on the server.",
        focused = focusedRow == 7,
        trailingLabel = if (testing) "Testing…" else "Test",
        onClick = { viewModel.testSmbServer(server.id) },
    )
    RowDivider()
    ActionRow(
        title = "Remove this server",
        icon = Icons.Rounded.Delete,
        subtitle = "Forgets the address and the password. Nothing on the server is " +
            "touched.",
        focused = focusedRow == 8,
        trailingLabel = "Remove",
        destructive = true,
        onClick = { viewModel.deleteSmbServer(server.id) },
    )
    RowDivider()
    ActionRow(
        title = "Done",
        icon = Icons.Rounded.Check,
        subtitle = "Back to the list",
        focused = focusedRow == 9,
        trailingLabel = "Done",
        onClick = { viewModel.editSmbServer(null) },
    )
}

/**
 * A server in a sentence.
 *
 * Worth the trouble because the list is otherwise a column of names with nothing
 * to tell them apart, and because it is the only confirmation that the fields
 * took — a row that says nothing about credentials is not obviously "signs in as
 * nobody".
 */
private fun describeServer(server: SmbServer): String {
    if (server.host.isBlank()) return "No address yet"

    val who = when {
        server.guest || server.username.isBlank() -> "as a guest"
        else -> "as ${server.username}"
    }
    val what = if (server.share.isBlank()) "every share" else server.share
    return "${server.host} · $what · $who"
}

/**
 * A typed address, with what people habitually type around it removed.
 *
 * `\\tower\games` is how Windows writes it and `smb://tower` is how a Mac does;
 * both are what somebody copying an address from another machine will paste. All
 * three forms mean the same server, and rejecting two of them would be pedantry
 * rather than validation.
 */
private fun String.cleanHost(): String = trim()
    .removePrefix("smb://")
    .removePrefix("//")
    .removePrefix("\\\\")
    .replace('\\', '/')
    .substringBefore('/')
    .trim()

/** Scan, then add-by-address, then anything found, then what is set up. */
private const val SCAN_ROW = 0
private const val ADD_ROW = 1
private const val DISCOVERED_FIRST_ROW = 2

/** Address, name, guest, user, password, domain, share, test, remove, done. */
internal const val SMB_SERVER_EDIT_ROWS = 10

internal fun networkSharesRows(
    serverCount: Int,
    discoveredCount: Int,
    editing: Boolean,
): Int = if (editing) {
    SMB_SERVER_EDIT_ROWS
} else {
    DISCOVERED_FIRST_ROW + discoveredCount + serverCount
}
