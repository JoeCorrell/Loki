package com.thor.feature.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SportsEsports
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.thor.core.designsystem.component.GlassSurface
import com.thor.core.designsystem.modifier.thorCursor
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.model.FileEntry
import com.thor.core.model.FileKind
import com.thor.core.model.formatFileSize
import com.thor.core.ui.input.LocalThorTextInput
import com.thor.core.ui.input.ThorInputField
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the cursor is on, and everything that can be done to it.
 *
 * The acting half of the pair, on the screen being held: the subject at the top,
 * the verbs beneath it as tiles, the switches that change what the list shows
 * under their own heading, and the volume along the foot.
 *
 * Every corner comes from [ThorTheme.shapes] rather than a literal radius, so the
 * panel turns square or round with the launcher's corner style — tiles, badges,
 * the switch and the storage bar together.
 */
@Composable
fun FileActionPanel(
    state: FilesUiState,
    actions: FileBrowserActions,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val motion = ThorTheme.motion

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        if (state.status is FilesStatus.NoAccess) {
            NoAccessPanel(actions.onGrantAccess)
            return@Box
        }

        /*
         * Four bands, and only one of them grows.
         *
         * There were six, each with its own heading and padding, and between them
         * they left the tiles too little height to draw in — the bottom row came
         * out clipped. The chrome is what gave way rather than the buttons: the
         * "Options" heading went (a switch captioned "Show or hide hidden files"
         * needs no heading to explain that it is an option), and the switch now
         * shares a row with the volume readout instead of stacking above it. That
         * is a whole band and a heading of height handed back to the grid.
         */
        Column(
            modifier = Modifier.fillMaxSize().padding(dimens.spacingSmall),
            verticalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
        ) {
            Subject(state)

            SectionHeading("Actions") {
                LayoutToggle(
                    layout = state.actionLayout,
                    onChange = actions.onActionLayoutChanged,
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ActionGrid(state, actions)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
            ) {
                HiddenSwitch(state, actions, modifier = Modifier.weight(1f))
                StorageBar(state, modifier = Modifier.weight(1f))
            }
        }

        AnimatedVisibility(
            visible = state.transfer != null,
            enter = fadeIn(motion.tweenSpec(motion.panelMillis)),
            exit = fadeOut(motion.tweenSpec(motion.panelMillis)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            state.transfer?.let { TransferBar(it) }
        }

        state.prompt?.let { prompt -> PromptDialog(prompt, actions) }
    }
}

// ---- What is being acted on ------------------------------------------------

/**
 * The thing the buttons below apply to, named before them.
 *
 * Reads differently for a marked set, because "Delete" with six files ticked is
 * about the six and not about whatever the cursor happens to be resting on. Since
 * marks now survive walking into another folder, it also says how many of them
 * are out of sight — that is the one way this can go badly wrong, and it should
 * not take a delete to find out.
 */
@Composable
private fun Subject(state: FilesUiState) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel
    val entry = state.subject
    val marked = state.marked.size

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.cursor.copy(alpha = SUBJECT_BORDER_ALPHA), shape)
            .padding(horizontal = dimens.spacingSmall, vertical = dimens.spacingTiny),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Preview(entry, markedCount = marked)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        marked > 1 -> "$marked items marked"
                        marked == 1 && entry == null -> "1 item marked"
                        entry != null -> entry.name
                        state.entries.isEmpty() -> "Nothing here"
                        else -> "Nothing selected"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Text(
                    text = when {
                        marked > 1 || (marked == 1 && entry == null) -> buildString {
                            /*
                             * The next step, said at the moment it is needed.
                             *
                             * Marking is the obvious way to pick a file up and it
                             * is only half the gesture — Paste stays dead until
                             * something is actually held, and a marked file with
                             * a greyed Paste button reads as the feature being
                             * broken rather than as a step being missed.
                             */
                            if (state.clipboard == null) {
                                append("Cut or Copy them, then Paste where they belong")
                            } else {
                                append("Every action applies to these")
                            }
                            if (state.markedElsewhere > 0) {
                                append("  ·  ${state.markedElsewhere} in other folders")
                            }
                        }

                        entry != null -> entry.summary()
                        else -> "Open a folder above to fill this in"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.markedElsewhere > 0) colors.cursor else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            state.clipboard?.let { clipboard ->
                Pill("${clipboard.verb} ${clipboard.paths.size}", accent = true)
            }

            (state.message ?: state.notice)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.message != null) colors.error else colors.cursor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = MESSAGE_WIDTH.dp),
                )
            }
        }
    }
}

/**
 * A picture where there is one, a glyph where there is not.
 *
 * Only images are previewed. Decoding a frame out of a video or a page out of a
 * PDF means running a media extractor over whatever the cursor touches, which on
 * a folder being scrolled through is a decode per row.
 */
@Composable
private fun Preview(entry: FileEntry?, markedCount: Int) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small

    Box(
        modifier = Modifier
            .size(PREVIEW_SIZE.dp)
            .clip(shape)
            .background(colors.cursor.copy(alpha = TILE_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            markedCount > 1 -> Text(
                text = "$markedCount",
                style = MaterialTheme.typography.titleMedium,
                color = colors.cursor,
                fontWeight = FontWeight.SemiBold,
            )

            entry == null -> Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = colors.cursor,
                modifier = Modifier.size(PREVIEW_GLYPH.dp),
            )

            entry.kind == FileKind.IMAGE -> AsyncImage(
                model = File(entry.path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            else -> Icon(
                imageVector = entry.kind.glyph(),
                contentDescription = null,
                tint = colors.cursor,
                modifier = Modifier.size(PREVIEW_GLYPH.dp),
            )
        }
    }
}

/**
 * What a button says beneath its label, given the state it is in.
 *
 * Only the ones whose disabled reason is not obvious from looking say anything
 * different. Paste is the one that matters: marking a file is the natural way to
 * pick something up, and nothing anywhere said that a mark and a clipboard are
 * two different things.
 */
private fun FileAction.describe(state: FilesUiState): String = when {
    this == FileAction.PASTE && state.clipboard == null -> "Cut or copy first"
    this == FileAction.EXTRACT && !isEnabled(state) -> "Zip archives only"
    else -> description
}

/** Kind, contents or size, and when it last changed. */
private fun FileEntry.summary(): String = buildString {
    append(kindLabel())
    if (isDirectory) {
        childCount?.let { append("  ·  ").append(if (it == 1) "1 item" else "$it items") }
    } else {
        append("  ·  ").append(formatFileSize(sizeBytes).ifEmpty { "Unknown size" })
    }
    append("  ·  ").append(modifiedEpochMs.asDateTime())
    if (!canWrite) append("  ·  read-only")
}

// ---- Headings --------------------------------------------------------------

/** A small caps label with a rule running out to whatever sits on the right. */
@Composable
private fun SectionHeading(
    title: String,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = HEADING_ALPHA),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.outline.copy(alpha = RULE_ALPHA)),
        )
        trailing?.invoke()
    }
}

@Composable
private fun LayoutToggle(layout: FilesActionLayout, onChange: (FilesActionLayout) -> Unit) {
    val dimens = ThorTheme.dimens

    Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacingTiny)) {
        ToggleButton(
            icon = Icons.Rounded.GridView,
            label = "Grid actions",
            active = layout == FilesActionLayout.GRID,
            onClick = { onChange(FilesActionLayout.GRID) },
        )
        ToggleButton(
            icon = Icons.AutoMirrored.Rounded.List,
            label = "List actions",
            active = layout == FilesActionLayout.LIST,
            onClick = { onChange(FilesActionLayout.LIST) },
        )
    }
}

@Composable
private fun ToggleButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small

    Box(
        modifier = Modifier
            .size(TOGGLE_BUTTON.dp)
            .clip(shape)
            .background(if (active) colors.cursor.copy(alpha = ACTIVE_ALPHA) else colors.surface)
            .border(
                1.dp,
                colors.outline.copy(alpha = if (active) 0f else RULE_ALPHA),
                shape,
            )
            .semantics { selected = active }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) colors.cursor else colors.onSurfaceVariant,
            modifier = Modifier.size(TOGGLE_GLYPH.dp),
        )
    }
}

// ---- The verbs -------------------------------------------------------------

/**
 * The verbs, in a grid whose shape the view model is told about.
 *
 * The column count is resolved here, from the width this panel actually got, and
 * reported upward — the same arrangement the settings screen uses for its row
 * count, and for the same reason. Without it the pad could only walk the buttons
 * in one dimension: the grid wraps to two rows, and a view model that does not
 * know how wide a row is cannot tell what is directly below a button. Up and Down
 * did nothing, which is exactly what it looked like.
 */
@Composable
private fun ActionGrid(state: FilesUiState, actions: FileBrowserActions) {
    val dimens = ThorTheme.dimens
    val animationsEnabled = ThorTheme.materials.animationsEnabled
    val gridState = rememberLazyGridState()
    val focusedPane = state.pane == FilesPane.ACTIONS
    val list = state.actionLayout == FilesActionLayout.LIST

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spacing = dimens.spacingSmall

        /*
         * Six across where there is room, which is twelve verbs in two rows.
         *
         * Stepped rather than derived from a minimum tile width. A purely adaptive
         * count produced nine and then three — a full row and a stub — which reads
         * as a mistake even though every tile fits. These fall to whole rows on a
         * narrower panel instead.
         */
        val columns = if (list) {
            1
        } else {
            when {
                maxWidth >= (TILE_MIN_WIDTH * 6).dp + spacing * 5 -> 6
                maxWidth >= (TILE_MIN_WIDTH * 4).dp + spacing * 3 -> 4
                maxWidth >= (TILE_MIN_WIDTH * 3).dp + spacing * 2 -> 3
                else -> 2
            }
        }

        /*
         * The tiles divide the height rather than taking a fixed one and
         * scrolling past the bottom of it.
         *
         * A panel whose last row is below the fold makes the pad walk into empty
         * space to find Delete, and on a screen this shape there is no reason for
         * any of it to be hidden — two rows is a comfortable fit, so the layout is
         * told to make it one rather than left to discover it does not.
         */
        val rows = (GRID_ACTIONS.size + columns - 1) / columns
        val tileHeight = if (list) {
            LIST_ROW_HEIGHT.dp
        } else {
            ((maxHeight - spacing * (rows - 1)) / rows).coerceAtLeast(TILE_MIN_HEIGHT.dp)
        }

        LaunchedEffect(columns) { actions.onActionColumnsChanged(columns) }

        // Only ever needed in list mode now; the grid has nowhere to scroll to.
        LaunchedEffect(state.actionCursor, focusedPane) {
            if (list && focusedPane && state.actionCursor < GRID_ACTIONS.size) {
                if (animationsEnabled) {
                    gridState.animateScrollToItem(state.actionCursor)
                } else {
                    gridState.scrollToItem(state.actionCursor)
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            userScrollEnabled = list,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(GRID_ACTIONS.size) { index ->
                val action = GRID_ACTIONS[index]
                ActionButton(
                    action = action,
                    enabled = action.isEnabled(state),
                    focused = focusedPane && index == state.actionCursor,
                    // Mark says whether the thing under the cursor is ticked,
                    // because a button reading "Mark" says nothing about that.
                    active = action == FileAction.MARK && state.focused?.path in state.marked,
                    asRow = list,
                    height = tileHeight,
                    // Under this there is room for a glyph and a label and no
                    // more, so the description goes rather than being clipped.
                    showDescription = list || tileHeight >= DESCRIPTION_FLOOR.dp,
                    description = action.describe(state),
                    onClick = {
                        actions.onFocusAction(index)
                        actions.onPerformAction(action)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    action: FileAction,
    enabled: Boolean,
    focused: Boolean,
    active: Boolean,
    asRow: Boolean,
    height: androidx.compose.ui.unit.Dp,
    showDescription: Boolean,
    /**
     * What the button says under its label, which is not always its own.
     *
     * A greyed-out button with a description of what it *would* do is the least
     * useful thing on the panel: the user can already see it is dead and still
     * has no idea why. Paste in particular reads as broken — marking a file is
     * the obvious way to pick it up, and nothing on screen said that a mark and a
     * clipboard are different things until this line did.
     */
    description: String,
    onClick: () -> Unit,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel

    // Delete wears the error colour wherever it appears, here and in the
    // confirmation it opens, so the one irreversible verb never looks like the
    // eleven reversible ones beside it.
    val tint = if (action == FileAction.DELETE) colors.error else colors.cursor

    val glyph = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        else -> tint
    }
    val label = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        focused || active -> tint
        else -> colors.onBackground
    }

    val plate = Modifier
        .clip(shape)
        .background(
            when {
                focused -> tint.copy(alpha = FOCUS_ALPHA)
                active -> tint.copy(alpha = ACTIVE_ALPHA)
                else -> colors.surface
            },
        )
        .border(
            1.dp,
            if (focused) tint else colors.outline.copy(alpha = RULE_ALPHA),
            shape,
        )
        .thorCursor(focused = focused, shape = shape)
        .clickable(
            enabled = enabled,
            onClickLabel = action.label,
            role = Role.Button,
            onClick = onClick,
        )

    if (asRow) {
        Row(
            modifier = plate.fillMaxWidth().height(height).padding(horizontal = dimens.spacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = glyph,
                modifier = Modifier.size(TILE_GLYPH.dp),
            )
            Column {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = label,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else DISABLED_ALPHA,
                    ),
                )
            }
        }
        return
    }

    Column(
        modifier = plate.height(height).padding(dimens.spacingTiny),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(TILE_GLYPH.dp),
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = label,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = dimens.spacingTiny),
        )
        if (showDescription) {
            Text(
                text = action.description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Options ---------------------------------------------------------------

/**
 * The one switch that changes what the list shows rather than what is in it.
 *
 * Drawn apart from the verbs above for that reason, and reachable from the pad by
 * pressing Down off the bottom row — it is the last entry in [FileAction], which
 * is what puts it directly below them in the grid arithmetic.
 */
@Composable
private fun HiddenSwitch(
    state: FilesUiState,
    actions: FileBrowserActions,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel

    val index = FileAction.entries.indexOf(FileAction.HIDDEN)
    val focused = state.pane == FilesPane.ACTIONS && state.actionCursor == index
    val on = state.showHidden

    Row(
        modifier = modifier
            .height(FOOT_HEIGHT.dp)
            .clip(shape)
            .background(colors.surface)
            .border(
                1.dp,
                if (focused) colors.cursor else colors.outline.copy(alpha = RULE_ALPHA),
                shape,
            )
            .thorCursor(focused = focused, shape = shape)
            .semantics { stateDescription = if (on) "On" else "Off" }
            .clickable(
                role = Role.Switch,
                onClick = {
                    actions.onFocusAction(index)
                    actions.onPerformAction(FileAction.HIDDEN)
                },
            )
            .padding(horizontal = dimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
    ) {
        Icon(
            imageVector = if (on) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
            contentDescription = null,
            tint = if (on) colors.cursor else colors.onSurfaceVariant,
            modifier = Modifier.size(OPTION_GLYPH.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = FileAction.HIDDEN.label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                text = FileAction.HIDDEN.description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(on)
    }
}

/**
 * A switch drawn from the theme's own pill.
 *
 * Not Material's, which has a fixed capsule and would be the one control on the
 * screen that stayed round when the launcher is set to hard corners.
 */
@Composable
private fun Switch(on: Boolean) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.pill

    Box(
        modifier = Modifier
            .width(SWITCH_WIDTH.dp)
            .height(SWITCH_HEIGHT.dp)
            .clip(shape)
            .background(if (on) colors.cursor else colors.surfaceElevated),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = SWITCH_INSET.dp)
                .size(SWITCH_KNOB.dp)
                .clip(shape)
                .background(if (on) colors.onSurface else colors.onSurfaceVariant),
        )
    }
}

// ---- The foot --------------------------------------------------------------

@Composable
private fun StorageBar(state: FilesUiState, modifier: Modifier = Modifier) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val space = state.space ?: return
    val shape = ThorTheme.shapes.panel

    Row(
        modifier = modifier
            .height(FOOT_HEIGHT.dp)
            .clip(shape)
            .background(colors.surface)
            .padding(horizontal = dimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        AccentTile(icon = Icons.Rounded.SdStorage, size = STORAGE_TILE, glyph = STORAGE_GLYPH)

        Text(
            text = "${formatFileSize(space.freeBytes)} free",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            maxLines = 1,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(BAR_HEIGHT.dp)
                .clip(ThorTheme.shapes.pill)
                .background(colors.surfaceElevated),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(space.usedFraction)
                    .fillMaxHeight()
                    .clip(ThorTheme.shapes.pill)
                    .background(Brush.horizontalGradient(colors.accentStops)),
            )
        }

        Text(
            text = "${(space.usedFraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun Pill(text: String, accent: Boolean = false) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Box(
        modifier = Modifier
            .clip(ThorTheme.shapes.pill)
            .background(
                if (accent) colors.cursor.copy(alpha = HELD_ALPHA) else colors.surfaceElevated,
            )
            .padding(horizontal = dimens.spacingSmall, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) colors.cursor else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun TransferBar(transfer: FileTransfer) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    GlassSurface(modifier = Modifier.fillMaxWidth().padding(dimens.spacing)) {
        Column(modifier = Modifier.padding(dimens.spacing)) {
            Text(
                text = "${transfer.label} — ${formatFileSize(transfer.copiedBytes)} " +
                    "of ${formatFileSize(transfer.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = dimens.spacingTiny)
                    .height(BAR_HEIGHT.dp)
                    .clip(ThorTheme.shapes.pill)
                    .background(colors.surfaceElevated),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(transfer.fraction)
                        .fillMaxHeight()
                        .clip(ThorTheme.shapes.pill)
                        .background(Brush.horizontalGradient(colors.accentStops)),
                )
            }
        }
    }
}

// ---- Prompts ---------------------------------------------------------------

@Composable
internal fun PromptDialog(prompt: FilesPrompt, actions: FileBrowserActions) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val textInput = LocalThorTextInput.current

    val title = when (prompt) {
        is FilesPrompt.Rename -> "Rename"
        is FilesPrompt.NewFolder -> "New folder"
        is FilesPrompt.Compress -> "Create a zip"
        is FilesPrompt.ConfirmDelete -> "Delete permanently?"
    }

    val body = when (prompt) {
        is FilesPrompt.ConfirmDelete -> buildString {
            if (prompt.paths.size == 1) {
                append(prompt.paths.first().substringAfterLast('/'))
                append(" will be gone.")
            } else {
                append("${prompt.paths.size} items will be gone.")
            }
            append(" There is no wastebasket to take ")
            append(if (prompt.paths.size == 1) "it" else "them")
            append(" out of.")

            /*
             * Said out loud when some of them are not on screen.
             *
             * Marks survive walking into another folder, which is what makes them
             * worth having — and is also the one way this can go badly wrong. A
             * count of what is about to be deleted out of sight belongs on the
             * confirmation, not in the release notes.
             */
            if (prompt.elsewhere > 0) {
                append("\n\n${prompt.elsewhere} of them ")
                append(if (prompt.elsewhere == 1) "is" else "are")
                append(" in other folders.")
            }
        }

        is FilesPrompt.Compress -> if (prompt.paths.size == 1) {
            "Packs ${prompt.paths.first().substringAfterLast('/')} into a zip here."
        } else {
            "Packs ${prompt.paths.size} items into one zip here."
        }

        else -> null
    }

    val fieldId = when (prompt) {
        is FilesPrompt.Rename -> "files-rename"
        is FilesPrompt.NewFolder -> "files-new-folder"
        is FilesPrompt.Compress -> "files-compress"
        else -> null
    }

    val value = when (prompt) {
        is FilesPrompt.Rename -> prompt.name
        is FilesPrompt.NewFolder -> prompt.name
        is FilesPrompt.Compress -> prompt.name
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(onClick = actions.onPromptDismissed),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            shape = ThorTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(DIALOG_FRACTION)
                .widthIn(max = DIALOG_WIDTH.dp)
                // Swallows taps so they do not reach the dismiss handler behind.
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(dimens.spacingLarge),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (prompt is FilesPrompt.ConfirmDelete) {
                        colors.error
                    } else {
                        colors.onBackground
                    },
                )

                body?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }

                if (fieldId != null) {
                    ThorInputField(
                        id = fieldId,
                        label = title,
                        value = value,
                        onValueChange = actions.onPromptTextChanged,
                        placeholder = "Name",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    /*
                     * The field takes the keyboard the moment the prompt opens.
                     *
                     * A naming dialog is opened in order to type, so making the
                     * user find the field first is a step with no decision in it.
                     * Keyed on the id alone: re-running this on every keystroke
                     * would re-seed the keyboard with the text it started with.
                     */
                    LaunchedEffect(fieldId) {
                        textInput.focus(
                            id = fieldId,
                            label = title,
                            initial = value,
                            onChange = actions.onPromptTextChanged,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall)) {
                    FilesButton(
                        label = when (prompt) {
                            is FilesPrompt.ConfirmDelete -> "Delete"
                            is FilesPrompt.Compress -> "Pack"
                            else -> "Save"
                        },
                        focused = prompt.confirmFocused,
                        destructive = prompt is FilesPrompt.ConfirmDelete,
                        onClick = actions.onPromptCommitted,
                        modifier = Modifier.weight(1f),
                    )
                    FilesButton(
                        label = "Cancel",
                        focused = !prompt.confirmFocused,
                        onClick = actions.onPromptDismissed,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ---- Shared with the browser ----------------------------------------------

/** The glyph that stands for a kind of file on both screens. */
internal fun FileKind.glyph(): ImageVector = when (this) {
    FileKind.FOLDER -> Icons.Rounded.Folder
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.AUDIO -> Icons.Rounded.MusicNote
    FileKind.ARCHIVE -> Icons.Rounded.Archive
    FileKind.DOCUMENT -> Icons.Rounded.Description
    FileKind.APP -> Icons.Rounded.Android
    FileKind.GAME -> Icons.Rounded.SportsEsports
    FileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

internal fun FileEntry.kindLabel(): String = when (kind) {
    FileKind.FOLDER -> "Folder"
    FileKind.IMAGE -> "Image"
    FileKind.VIDEO -> "Video"
    FileKind.AUDIO -> "Audio"
    FileKind.ARCHIVE -> "Archive"
    FileKind.DOCUMENT -> "Document"
    FileKind.APP -> "Android app"
    FileKind.GAME -> "Game"
    FileKind.OTHER -> if (extension.isEmpty()) "File" else extension.uppercase()
}

/**
 * A timestamp, written out.
 *
 * Absolute rather than relative. "3 days ago" is friendlier and useless for the
 * thing this line is read for, which is telling two copies of the same file apart.
 */
internal fun Long.asDateTime(): String =
    if (this <= 0L) "Unknown" else DATE_FORMAT.format(Date(this))

private val DATE_FORMAT = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

private const val PREVIEW_SIZE = 38
private const val PREVIEW_GLYPH = 20
/** The narrowest a tile may be before the grid drops to fewer columns. */
private const val TILE_MIN_WIDTH = 84

/** A floor, so a very short panel scrolls rather than crushing the tiles. */
private const val TILE_MIN_HEIGHT = 52

/** Under this a tile has room for a glyph and a label, and nothing else. */
private const val DESCRIPTION_FLOOR = 72

private const val LIST_ROW_HEIGHT = 56
private const val TILE_GLYPH = 22
private const val TOGGLE_BUTTON = 26
private const val TOGGLE_GLYPH = 15
private const val OPTION_GLYPH = 20
private const val STORAGE_TILE = 26
private const val STORAGE_GLYPH = 15

/** One height for both feet, so the pair reads as a single band. */
private const val FOOT_HEIGHT = 44
private const val SWITCH_WIDTH = 38
private const val SWITCH_HEIGHT = 21
private const val SWITCH_KNOB = 15
private const val SWITCH_INSET = 3
private const val BAR_HEIGHT = 7
private const val MESSAGE_WIDTH = 200
private const val DIALOG_WIDTH = 420
private const val DIALOG_FRACTION = 0.78f

private const val TILE_ALPHA = 0.16f
private const val HELD_ALPHA = 0.18f
private const val FOCUS_ALPHA = 0.16f
private const val ACTIVE_ALPHA = 0.13f
private const val DISABLED_ALPHA = 0.35f
private const val SUBJECT_BORDER_ALPHA = 0.55f
private const val HEADING_ALPHA = 0.75f
private const val RULE_ALPHA = 0.35f
