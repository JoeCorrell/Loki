package com.thor.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thor.core.designsystem.component.GlassSurface
import com.thor.core.designsystem.modifier.thorCursor
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.model.FileEntry
import com.thor.core.model.formatFileSize
import com.thor.data.files.FileShortcut

/**
 * The explorer's listing, on the reading screen.
 *
 * The browsing half of the pair. Nothing here describes what the cursor is on and
 * nothing here acts on it — both of those are the panel below — which is what
 * leaves this screen able to spend its whole height on rows.
 *
 * Everything is built from [ThorTheme.shapes], never from a literal corner
 * radius, so the whole screen turns square or round with the launcher's corner
 * style. That includes the button badges along the foot: a circular "A" in a
 * launcher set to hard corners is the one element that would refuse to join in.
 *
 * @param compact folds the description and the buttons in underneath, for the
 *   cases where there is no second panel to put them on: couch mode, and a
 *   handheld whose other screen has been taken by a running app.
 */
@Composable
fun FileBrowserScreen(
    state: FilesUiState,
    actions: FileBrowserActions,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(dimens.spacing),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Header(state, actions)

            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                ShortcutRail(state, actions)

                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
                ) {
                    GlassSurface(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        val status = state.status
                        when {
                            status is FilesStatus.NoAccess ->
                                NoAccessPanel(actions.onGrantAccess)

                            status is FilesStatus.Problem -> CentredNote(status.message)

                            status is FilesStatus.Loading && state.entries.isEmpty() ->
                                CentredNote("Reading…")

                            state.entries.isEmpty() -> CentredNote("This folder is empty")

                            else -> Listing(state, actions)
                        }
                    }

                    if (compact) {
                        FileActionPanel(
                            state = state,
                            actions = actions,
                            modifier = Modifier.fillMaxWidth().height(COMPACT_PANEL_HEIGHT.dp),
                        )
                    } else {
                        HintBar(state)
                    }
                }
            }
        }
    }
}

/** Everything either panel can ask for, in one place so the shell wires it once. */
data class FileBrowserActions(
    val onFocusEntry: (Int) -> Unit,
    val onOpenEntry: (FileEntry) -> Unit,
    val onToggleMark: (String) -> Unit,
    val onFocusShortcut: (Int) -> Unit,
    val onOpenShortcut: (FileShortcut) -> Unit,
    val onOpenCrumb: (String) -> Unit,
    val onFocusAction: (Int) -> Unit,
    val onActionColumnsChanged: (Int) -> Unit,
    val onActionLayoutChanged: (FilesActionLayout) -> Unit,
    val onPerformAction: (FileAction) -> Unit,
    val onPromptTextChanged: (String) -> Unit,
    val onPromptFocusConfirm: (Boolean) -> Unit,
    val onPromptCommitted: () -> Unit,
    val onPromptDismissed: () -> Unit,
    val onGrantAccess: () -> Unit,
    val onClose: () -> Unit,
)

// ---- The header ------------------------------------------------------------

@Composable
private fun Header(state: FilesUiState, actions: FileBrowserActions) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        AccentTile(icon = Icons.Rounded.Folder, size = HEADER_TILE)

        Column(modifier = Modifier.weight(1f)) {
            /*
             * The folder's own name, rather than the word "Files".
             *
             * A title that never changes tells the reader nothing on any screen
             * after the first. Where they *are* is the one thing a browser's
             * heading can usefully say, and the crumbs below carry how they got
             * there.
             */
            Text(
                text = state.crumbs.lastOrNull()?.label ?: "Files",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Breadcrumbs(state, actions)
        }

        StatsCard(state)

        GlassSurface(shape = ThorTheme.shapes.panel) {
            Row(
                modifier = Modifier.padding(dimens.spacingTiny),
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingTiny),
            ) {
                /*
                 * The two switches worth having on this screen, and the way down
                 * to the rest.
                 *
                 * The reference sketch had a list/grid toggle here. There is no
                 * grid view to toggle to, and a control that does nothing is worse
                 * than an empty corner — so the slot goes to the two settings that
                 * change what this list *shows*, and the overflow does the one
                 * thing an overflow should, which is reach everything else.
                 */
                HeaderButton(
                    icon = Icons.Rounded.SwapVert,
                    label = "Change sort order",
                    active = false,
                    onClick = { actions.onPerformAction(FileAction.SORT) },
                )
                HeaderButton(
                    icon = if (state.showHidden) {
                        Icons.Rounded.Visibility
                    } else {
                        Icons.Rounded.VisibilityOff
                    },
                    active = state.showHidden,
                    label = if (state.showHidden) "Hide hidden files" else "Show hidden files",
                    onClick = { actions.onPerformAction(FileAction.HIDDEN) },
                )
                HeaderButton(
                    icon = Icons.Rounded.MoreVert,
                    label = "File actions",
                    active = state.pane == FilesPane.ACTIONS,
                    onClick = { actions.onFocusAction(0) },
                )
            }
        }
    }
}

/**
 * The path, as buttons rather than as a line of text.
 *
 * Scrolled horizontally and kept scrolled to its end, because the useful half of
 * a long path is the right-hand end — a header showing
 * "/storage/emulated/0/Android/data/com…" has spent its width on the part every
 * path on the device shares.
 */
@Composable
private fun Breadcrumbs(state: FilesUiState, actions: FileBrowserActions) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val animationsEnabled = ThorTheme.materials.animationsEnabled
    val scroll = rememberScrollState()

    LaunchedEffect(state.path) {
        if (animationsEnabled) {
            scroll.animateScrollTo(scroll.maxValue)
        } else {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = CHEVRON_ALPHA),
                    modifier = Modifier.size(CRUMB_CHEVRON.dp),
                )
            }
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == state.crumbs.lastIndex) {
                    colors.onSurface
                } else {
                    colors.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(ThorTheme.shapes.small)
                    .clickable(role = Role.Button) { actions.onOpenCrumb(crumb.path) }
                    .padding(horizontal = dimens.spacingTiny, vertical = 2.dp),
            )
        }
    }
}

/** Free space, position in the listing, and the order it is in. */
@Composable
private fun StatsCard(state: FilesUiState) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    GlassSurface(shape = ThorTheme.shapes.panel) {
        Row(
            modifier = Modifier.padding(
                horizontal = dimens.spacing,
                vertical = dimens.spacingSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            state.space?.let { space ->
                AccentTile(icon = Icons.Rounded.SdStorage, size = STATS_TILE, glyph = STATS_GLYPH)
                Column {
                    Text(
                        text = formatFileSize(space.freeBytes),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                    Text(
                        text = "free",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(DIVIDER_HEIGHT.dp)
                        .background(colors.outline.copy(alpha = RULE_ALPHA)),
                )
            }

            Column {
                Text(
                    text = buildString {
                        // Where the cursor is, not only how much there is. A folder
                        // of four thousand ROMs scrolls for a long time and the rows
                        // give no sense of progress through it.
                        if (state.entries.isNotEmpty()) {
                            append(state.cursor + 1)
                            append(" of ")
                        }
                        append(state.entries.size)
                        append(if (state.entries.size == 1) " item" else " items")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = buildString {
                        append(state.sort.label)
                        append(if (state.descending) "  ↓" else "  ↑")
                        if (state.marked.isNotEmpty()) append("  ·  ${state.marked.size} marked")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.marked.isNotEmpty()) colors.cursor else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HeaderButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small

    Box(
        modifier = Modifier
            .size(HEADER_BUTTON.dp)
            .clip(shape)
            .background(if (active) colors.cursor.copy(alpha = ACTIVE_ALPHA) else Color.Transparent)
            .semantics { selected = active }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) colors.cursor else colors.onSurfaceVariant,
            modifier = Modifier.size(HEADER_BUTTON_GLYPH.dp),
        )
    }
}

// ---- The rail --------------------------------------------------------------

@Composable
private fun ShortcutRail(state: FilesUiState, actions: FileBrowserActions) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val inRail = state.pane == FilesPane.SHORTCUTS
    val here = state.deepestShortcutPath

    GlassSurface(modifier = Modifier.width(RAIL_WIDTH.dp).fillMaxHeight()) {
        Column(modifier = Modifier.fillMaxSize().padding(dimens.spacingSmall)) {
            RailSection("THIS DEVICE")

            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(state.shortcuts.size) { index ->
                    val shortcut = state.shortcuts[index]

                    /*
                     * A heading above the first share.
                     *
                     * Shares are appended after the device's own volumes, so this
                     * fires once — at the boundary — and says what the rows under
                     * it are. Without it a NAS is just another row in a list
                     * headed "storage", which is the one thing it is not: it can
                     * be unreachable, it is slower, and it belongs to a machine
                     * that is not this one.
                     */
                    if (shortcut.remote && (index == 0 || !state.shortcuts[index - 1].remote)) {
                        RailSection("NETWORK")
                    }

                    ShortcutRow(
                        shortcut = shortcut,
                        focused = inRail && index == state.shortcutCursor,
                        /*
                         * Lit without a ring while the cursor is in the listing,
                         * so the rail still says which of these places you are
                         * inside.
                         *
                         * The *deepest* match only. Every path under internal
                         * storage also begins with internal storage, so a plain
                         * prefix test lit three rows at once — three answers to a
                         * question that has one.
                         */
                        here = !inRail && shortcut.path == here,
                        onClick = {
                            actions.onFocusShortcut(index)
                            actions.onOpenShortcut(shortcut)
                        },
                    )
                }

                /*
                 * Where network shares come from, said in the place people look
                 * for them.
                 *
                 * Shown only when there are none, and it is the answer to a
                 * question this screen was otherwise silent about: a rail listing
                 * nothing but the device's own volumes gives no indication that
                 * shares exist at all, so somebody who has one is left looking at
                 * "Internal storage" and wondering where their NAS went. Not
                 * focusable — the explorer cannot open Settings, and a row that
                 * takes the cursor and then refuses to do anything is worse than a
                 * sentence.
                 */
                if (state.shortcuts.none(FileShortcut::remote)) {
                    item {
                        RailSection("NETWORK")
                        Text(
                            text = "Add a server in Settings › System › Network shares",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = SECTION_ALPHA),
                            modifier = Modifier.padding(
                                start = dimens.spacingSmall,
                                end = dimens.spacingSmall,
                                bottom = dimens.spacingSmall,
                            ),
                        )
                    }
                }
            }

            /*
             * Said once, at the foot of the rail, rather than discovered.
             *
             * The pad drives every part of this screen and none of that is
             * visible: there is no cursor to see until a button is pressed, and
             * nothing about a file list suggests a controller is the way through
             * it. The hint costs a corner nothing else wanted.
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.spacingSmall)
                    .clip(ThorTheme.shapes.small)
                    .background(colors.surfaceElevated)
                    .padding(dimens.spacingSmall),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SportsEsports,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(HINT_GLYPH.dp),
                )
                Text(
                    text = "Use your controller to navigate and manage files",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** A heading in the rail: drawn, never focused, so it costs the cursor nothing. */
@Composable
private fun RailSection(title: String) {
    val dimens = ThorTheme.dimens
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = ThorTheme.colors.onSurfaceVariant.copy(alpha = SECTION_ALPHA),
        modifier = Modifier.padding(
            start = dimens.spacingSmall,
            top = dimens.spacingSmall,
            bottom = dimens.spacingSmall,
        ),
    )
}

@Composable
private fun ShortcutRow(
    shortcut: FileShortcut,
    focused: Boolean,
    here: Boolean,
    onClick: () -> Unit,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.small
    val lit = focused || here

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (lit) colors.cursor.copy(alpha = ACTIVE_ALPHA) else Color.Transparent)
            .thorCursor(focused = focused, shape = shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(dimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        Icon(
            // A share is marked, because it behaves differently enough to be worth
            // knowing before you press it: it can be unreachable, it is slower, and
            // a copy onto it is a transfer rather than a filesystem operation.
            imageVector = if (shortcut.remote) Icons.Rounded.Lan else Icons.Rounded.SdStorage,
            contentDescription = null,
            tint = if (lit) colors.cursor else colors.onSurfaceVariant,
            modifier = Modifier.size(RAIL_GLYPH.dp),
        )
        Text(
            text = shortcut.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (lit) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- The listing -----------------------------------------------------------

@Composable
private fun Listing(state: FilesUiState, actions: FileBrowserActions) {
    val dimens = ThorTheme.dimens
    val animationsEnabled = ThorTheme.materials.animationsEnabled
    val listState = rememberLazyListState()
    val inListing = state.pane != FilesPane.SHORTCUTS

    /*
     * The list follows the cursor by exactly as much as it has to.
     *
     * The distance is worked out in every case, including when the row the cursor
     * has moved onto has not been composed yet — which is the case that was wrong,
     * and wrong asymmetrically. Falling back to `scrollToItem` there looks correct
     * going *up*, because that call puts the row at the top of the viewport and the
     * top is exactly where a row being reached upward belongs. Going down it is a
     * whole-screen jump: the row one step below the fold gets dragged all the way
     * up to the first position. Same line of code, fine in one direction and
     * useless in the other, which is why only one of them was ever reported.
     *
     * Rows here are a fixed height — one line of name over one line of detail, both
     * capped — so the gap to an uncomposed row is arithmetic rather than a guess:
     * the rows between it and the edge, plus however much of the edge row is
     * already hanging over. Uniformity is the assumption, and it is one this
     * listing enforces rather than hopes for.
     *
     * Short moves are animated and long ones are not. A step onto the next row is a
     * few dozen points and reads as a nudge; landing on a cursor restored three
     * thousand rows down is not a journey anybody wants to watch. Holding a
     * direction cancels the animation in flight — `LaunchedEffect` restarts on the
     * new cursor — so it continues from wherever it had reached rather than
     * queueing, and a held press still keeps up.
     */
    LaunchedEffect(state.cursor, state.entries.size) {
        if (state.entries.isEmpty()) return@LaunchedEffect
        val cursor = state.cursor.coerceAtMost(state.entries.lastIndex)
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo

        // Nothing measured yet: there is no distance to compute against.
        if (visible.isEmpty()) {
            listState.scrollToItem(cursor)
            return@LaunchedEffect
        }

        val rowHeight = visible.first().size
        if (rowHeight <= 0) return@LaunchedEffect

        val onScreen = visible.firstOrNull { it.index == cursor }
        val first = visible.first()
        val last = visible.last()

        val distance = when {
            // Composed, and hanging over one edge or the other: close the overhang
            // and no more, so the row slides just into view.
            onScreen != null && onScreen.offset < info.viewportStartOffset ->
                onScreen.offset - info.viewportStartOffset

            onScreen != null && onScreen.offset + onScreen.size > info.viewportEndOffset ->
                onScreen.offset + onScreen.size - info.viewportEndOffset

            onScreen != null -> 0

            // Not composed, above: bring it to the top edge.
            cursor < first.index ->
                (cursor - first.index) * rowHeight + (first.offset - info.viewportStartOffset)

            // Not composed, below: bring it to the bottom edge. This is the one
            // that used to jump.
            else ->
                (cursor - last.index) * rowHeight +
                    (last.offset + last.size - info.viewportEndOffset)
        }

        when {
            distance == 0 -> Unit
            animationsEnabled &&
                kotlin.math.abs(distance) <= rowHeight * SMOOTH_ROWS ->
                listState.animateScrollBy(distance.toFloat())

            else -> listState.scrollBy(distance.toFloat())
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(dimens.spacingSmall),
    ) {
        items(state.entries.size) { index ->
            val entry = state.entries[index]
            FileRow(
                entry = entry,
                focused = inListing && index == state.cursor,
                marked = entry.path in state.marked,
                onClick = {
                    actions.onFocusEntry(index)
                    actions.onOpenEntry(entry)
                },
                onMarkTapped = {
                    actions.onFocusEntry(index)
                    actions.onToggleMark(entry.path)
                },
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    focused: Boolean,
    marked: Boolean,
    onClick: () -> Unit,
    onMarkTapped: () -> Unit,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(
                when {
                    focused -> colors.cursor.copy(alpha = FOCUS_ALPHA)
                    marked -> colors.cursor.copy(alpha = MARKED_ALPHA)
                    else -> colors.surfaceElevated.copy(alpha = ROW_ALPHA)
                },
            )
            .thorCursor(focused = focused, shape = shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = dimens.spacing, vertical = dimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        /*
         * The kind glyph is also the tick box.
         *
         * Marking has to be reachable by touch as well as from the pad, and the
         * alternatives were worse: a long press is a gesture nothing on this
         * screen announces, and a checkbox column costs every row width for
         * something used on a handful of them. The icon becomes a tick once
         * marked, so the target explains itself after the first press.
         */
        AccentTile(
            icon = if (marked) Icons.Rounded.CheckCircle else entry.kind.glyph(),
            size = ROW_TILE,
            glyph = ROW_GLYPH,
            accessibilityLabel = if (marked) "Unmark ${entry.name}" else "Mark ${entry.name}",
            onClick = onMarkTapped,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.kindLabel()}  ·  ${entry.modifiedEpochMs.asDateTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        entry.badge()?.let { badge ->
            Box(
                modifier = Modifier
                    .clip(ThorTheme.shapes.pill)
                    .background(colors.surfaceElevated)
                    .padding(horizontal = dimens.spacingSmall, vertical = 3.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = CHEVRON_ALPHA),
            modifier = Modifier.size(ROW_CHEVRON.dp),
        )
    }
}

/**
 * The pill at the end of a row: how many things are inside, or how big it is.
 *
 * Null for a folder nobody counted, and that is the whole reason [FileEntry
 * .childCount] is nullable — a folder reading "0 items" because the count was
 * skipped is a wrong answer, where no pill at all is an absent one.
 */
private fun FileEntry.badge(): String? = when {
    isDirectory -> childCount?.let { if (it == 1) "1 item" else "$it items" }
    sizeBytes >= 0 -> formatFileSize(sizeBytes)
    else -> null
}

// ---- Shared furniture ------------------------------------------------------

/**
 * The accent-washed tile the whole screen is built from.
 *
 * One shape, from the theme, so the header mark, the drive badge and every row
 * icon turn square or round together with the rest of the launcher rather than
 * being the three places that kept their own radius.
 */
@Composable
internal fun AccentTile(
    icon: ImageVector,
    size: Int,
    glyph: Int = size / 2,
    accessibilityLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(colors.cursor.copy(alpha = TILE_ALPHA))
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = accessibilityLabel,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = accessibilityLabel,
            tint = colors.cursor,
            modifier = Modifier.size(glyph.dp),
        )
    }
}

@Composable
internal fun NoAccessPanel(onGrant: () -> Unit) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Column(
        modifier = Modifier.fillMaxSize().padding(dimens.spacingLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(NOTICE_GLYPH.dp),
        )
        Text(
            text = "Loki cannot read your storage yet",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.padding(top = dimens.spacing),
        )
        Text(
            text = "Android puts whole-device file access behind a switch of its own. " +
                "Turning it on opens Android's settings; come back and this fills in.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = dimens.spacingSmall),
        )
        FilesButton(
            label = "Open Android settings",
            focused = true,
            onClick = onGrant,
            modifier = Modifier.padding(top = dimens.spacing).width(GRANT_WIDTH.dp),
        )
    }
}

@Composable
private fun CentredNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = ThorTheme.colors.onSurfaceVariant,
        )
    }
}

/**
 * What the buttons do, said out loud.
 *
 * A file manager on a pad has no discoverable gestures — nothing here is a swipe
 * or a long press that announces itself — so the bindings are drawn on the screen
 * rather than left to be found. Only where the action panel is on the other
 * screen; in compact mode the buttons are right there with their labels on them.
 */
@Composable
private fun HintBar(state: FilesUiState) {
    val dimens = ThorTheme.dimens

    val hints: List<Pair<String, String>> = when {
        state.prompt != null -> listOf("A" to "Confirm", "B" to "Cancel")
        state.isBusy -> listOf("B" to "Stop copying")
        state.pane == FilesPane.ACTIONS -> listOf("A" to "Do it", "B" to "Back to the list")
        state.pane == FilesPane.SHORTCUTS -> listOf("A" to "Go there", "B" to "Back to the list")
        else -> listOf(
            "A" to "Open",
            "B" to "Up a level",
            "Y" to "Actions",
            "X" to "Mark",
        )
    }

    GlassSurface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.spacing, vertical = dimens.spacingSmall),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingLarge),
        ) {
            hints.forEach { (button, label) -> Hint(button, label) }

            if (state.pane == FilesPane.LISTING && !state.isBusy && state.prompt == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spacingTiny),
                ) {
                    ShoulderBadge("LB")
                    Text(
                        text = "/",
                        style = MaterialTheme.typography.labelMedium,
                        color = ThorTheme.colors.onSurfaceVariant,
                    )
                    ShoulderBadge("RB")
                    Text(
                        text = "Sort",
                        style = MaterialTheme.typography.labelMedium,
                        color = ThorTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = dimens.spacingTiny),
                    )
                }
            }
        }
    }
}

@Composable
private fun Hint(button: String, label: String) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        Box(
            modifier = Modifier
                .size(BUTTON_BADGE.dp)
                // The theme's pill, not a circle: a round "A" on a launcher set to
                // hard corners is the one element that refuses to join in.
                .clip(ThorTheme.shapes.pill)
                .background(colors.cursor.copy(alpha = BADGE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = button,
                style = MaterialTheme.typography.labelMedium,
                color = colors.cursor,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ShoulderBadge(label: String) {
    val colors = ThorTheme.colors

    Box(
        modifier = Modifier
            .clip(ThorTheme.shapes.small)
            .background(colors.surfaceElevated)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A plain focusable button, shared by the panels and the prompts. */
@Composable
internal fun FilesButton(
    label: String,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small
    val tint = if (destructive) colors.error else colors.cursor

    Box(
        modifier = modifier
            .clip(shape)
            .background(if (focused) tint.copy(alpha = 0.22f) else colors.surfaceElevated)
            .thorCursor(focused = focused, shape = shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = ThorTheme.dimens.spacingSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) tint else colors.onSurfaceVariant,
        )
    }
}

/** Up to this many rows of travel is animated; past it, a jump is kinder. */
private const val SMOOTH_ROWS = 4

private const val RAIL_WIDTH = 176
private const val HEADER_TILE = 46
private const val STATS_TILE = 30
private const val STATS_GLYPH = 17
private const val HEADER_BUTTON = 34
private const val HEADER_BUTTON_GLYPH = 19
private const val ROW_TILE = 38
private const val ROW_GLYPH = 21
private const val ROW_CHEVRON = 18
private const val RAIL_GLYPH = 19
private const val HINT_GLYPH = 18
private const val BUTTON_BADGE = 22
private const val CRUMB_CHEVRON = 14
private const val NOTICE_GLYPH = 44
private const val GRANT_WIDTH = 280
private const val DIVIDER_HEIGHT = 26

private const val TILE_ALPHA = 0.16f
private const val ACTIVE_ALPHA = 0.18f
private const val FOCUS_ALPHA = 0.16f
private const val MARKED_ALPHA = 0.10f
private const val ROW_ALPHA = 0.5f
private const val BADGE_ALPHA = 0.20f
private const val CHEVRON_ALPHA = 0.6f
private const val RULE_ALPHA = 0.4f
private const val SECTION_ALPHA = 0.7f

/** Enough for the description and two rows of buttons, when both share a screen. */
private const val COMPACT_PANEL_HEIGHT = 190
