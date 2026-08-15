package com.moonlight.ds.settings.component.panel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moonlight.ds.settings.component.revealWhenFocused
import com.thor.core.designsystem.modifier.thorCursor
import com.thor.core.ui.pointer.pointerHover
import com.thor.core.ui.pointer.rememberPointerHover
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.model.AccentSlot
import com.moonlight.ds.settings.component.row.ActivateOnConfirm

/*
 * The vocabulary every page in the app is written in.
 *
 * These started inside the container detail panel, moved next door when the game
 * panel was rebuilt to match it, and are here now because the settings screens
 * and the container editor want the same idiom — which was the stated condition
 * for promoting them out of one screen's file.
 *
 * The idiom is four things, and it is worth naming them because they are what
 * makes a page recognisable:
 *
 *  - **A hero.** Every page opens with one card saying what it is: a tile, a
 *    coloured eyebrow, a title, a sentence.
 *  - **Groups under coloured headings**, not cards floating in space. The
 *    heading is the accent colour and the rows below it are one surface.
 *  - **Rows separated by hairlines**, flush and identically sized, with a
 *    tinted icon tile on the left of each.
 *  - **A colour per kind of thing**, resolved through the theme — see [IconTints].
 *
 * The rows are drawn flush and divided rather than as a stack of bordered cards
 * for a reason worth keeping: a card says "this is a separate thing", and eight
 * separate things under one heading is a heading that is not doing its job.
 */

// ---- The page ---------------------------------------------------------------

/**
 * The band a page opens with.
 *
 * Its whole job is to answer "where am I" without being read: the tile and the
 * eyebrow are recognised at a glance, and the title only has to confirm them.
 * That is why the eyebrow repeats what the rail already says — the rail is on
 * the far side of the screen, and by the time you are reading a row you are not
 * looking at it.
 *
 * [art] is the decorative cluster on the right. It is drawn from the same icon
 * set as everything else at a size and alpha where it reads as texture rather
 * than as controls, which is the only honest way to have decoration on a screen
 * navigated with a d-pad: it must be impossible to mistake for a target.
 */
@Composable
fun PageHero(
    eyebrow: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    art: List<ImageVector> = emptyList(),
    /**
     * The colour this page is painted with; the accent when nothing says.
     *
     * A parameter rather than a read of the accent, because the point of the
     * category wheel is that Games is green and Controls is red *on the same
     * theme* — a hero that resolved its own colour could only ever be one of
     * them. Defaulted so a caller with no category still gets a hero that
     * belongs to the palette.
     */
    tint: Color = ThorTheme.colors.primary,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceElevated, shape)
            /*
             * The wash runs to the right, under the artwork.
             *
             * Left to right rather than top to bottom because the title is on
             * the left and has to stay on a flat field — a vertical gradient
             * puts a value change directly behind the one thing on the card
             * that has to be legible.
             */
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, tint.copy(alpha = HERO_WASH)),
                ),
                shape,
            )
            .border(1.dp, colors.outline.copy(alpha = PANEL_BORDER_ALPHA), shape),
    ) {
        if (art.isNotEmpty()) {
            HeroArt(art = art, modifier = Modifier.align(Alignment.CenterEnd))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = dimens.spacing,
                    // Keeps the title off the artwork. The cluster is faint
                    // enough to read text over and that is still the wrong
                    // thing to do — a subtitle crossing a 40dp glyph is
                    // legible and looks like a mistake.
                    end = if (art.isEmpty()) dimens.spacing else HERO_ART_RESERVE.dp,
                    top = HERO_PAD.dp,
                    bottom = HERO_PAD.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
        ) {
            if (onBack != null) {
                IconButtonTile(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                )
            }

            Box(
                modifier = Modifier
                    .size(HERO_TILE.dp)
                    .clip(ThorTheme.shapes.small)
                    .background(tint.copy(alpha = HERO_TILE_FILL), ThorTheme.shapes.small)
                    .border(1.dp, tint.copy(alpha = HERO_TILE_BORDER), ThorTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(HERO_TILE_ICON.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = eyebrow.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = HEADING_TRACKING.sp,
                    color = colors.cursor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The cluster of oversized glyphs on the right of a hero.
 *
 * Overlapped and stepped in size so it reads as one object rather than as three
 * icons in a row, and clipped by the card so the largest runs off the edge —
 * which is what stops it looking like content that failed to fit.
 */
@Composable
private fun HeroArt(art: List<ImageVector>, modifier: Modifier = Modifier) {
    val colors = ThorTheme.colors

    Row(
        modifier = modifier.padding(end = HERO_ART_INSET.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-HERO_ART_OVERLAP).dp),
    ) {
        art.take(HERO_ART_MAX).forEachIndexed { index, glyph ->
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier
                    // Stepped rather than uniform: three identical shapes at
                    // three positions is a pattern, and a pattern reads as a
                    // control strip.
                    .size((HERO_ART_BASE + index * HERO_ART_STEP).dp)
                    .offset(y = (index * HERO_ART_RISE).dp)
                    .alpha(HERO_ART_ALPHA + index * HERO_ART_ALPHA_STEP),
            )
        }
    }
}

/**
 * The heading over a group of rows, on a settings page.
 *
 * Uppercase and in the accent colour, sitting on the page rather than on any
 * surface. That is the whole of it — no bar, no rule, no card. It is the single
 * strongest signal that two runs of rows are two different subjects, and adding
 * anything to it starts to say "separate object" when the object is the group
 * underneath.
 *
 * The editor's heading is a different shape and lives in [CardHeading]; see
 * [PageGroup] for which goes where.
 */
@Composable
fun GroupHeading(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = ThorTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HEADING_INSET.dp, bottom = HEADING_GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = HEADING_TRACKING.sp,
                color = colors.cursor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * The heading a group gets inside the container editor: a bar, a name, a line
 * saying what the group is for.
 *
 * Different from [GroupHeading] because the editor is a different job. A
 * settings page names one preference per row and the reader already knows what
 * they came for; an editor tab is fourteen rows of Direct3D vocabulary, and the
 * sentence under each heading is doing real work. Putting it inside the card
 * attaches it to the rows it explains rather than leaving it floating between
 * two groups.
 */
@Composable
private fun CardHeading(title: String, description: String?) {
    val colors = ThorTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(HEADING_BAR_GAP.dp),
    ) {
        Box(
            modifier = Modifier
                .width(HEADING_BAR_WIDTH.dp)
                .height(if (description == null) HEADING_BAR_SHORT.dp else HEADING_BAR_TALL.dp)
                .clip(ThorTheme.shapes.pill)
                .background(colors.cursor),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = HEADING_TRACKING.sp,
                color = colors.cursor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * The surface a group of rows sits on, and the thing that makes the hairlines
 * between them work.
 *
 * Each row draws its own hairline along its *top* edge — see [RowSurface] — and
 * this shifts the whole stack up by exactly that hairline so the first one is
 * clipped away. What is left is a line between every pair of rows and none
 * above the first or below the last, which is what a divided list is.
 *
 * That is a strange-looking trick and the alternatives are worse. The content
 * is an opaque lambda: this composable cannot know how many children it holds
 * or which is last, so it cannot place dividers between them. Making the
 * *gaps* the dividers — fill the container with the line colour and let opaque
 * rows leave one pixel showing — works for rows and fails for everything else,
 * because a group containing a button or a paragraph would draw it on a band
 * of divider colour. Clipping one line off the top costs nothing and holds for
 * any content.
 *
 * The 1dp lost at the bottom is card colour against card colour, so nothing
 * moves and nothing shows.
 */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.panel

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint ?: colors.surfaceElevated, shape)
            .border(1.dp, colors.outline.copy(alpha = PANEL_BORDER_ALPHA), shape),
    ) {
        Column(modifier = Modifier.offset(y = -(1.dp)), content = content)
    }
}

/**
 * The hairline a row draws above itself.
 *
 * On the row rather than between rows because only the row knows it is there.
 * See [GroupCard] for how the first one is disposed of.
 */
private fun Modifier.topHairline(color: Color): Modifier = drawBehind {
    drawRect(color = color, size = Size(size.width, 1.dp.toPx()))
}

/**
 * True inside the container editor, where a group's heading goes in its card.
 *
 * A local rather than a parameter on all twenty-two `WemuSection` calls in the
 * editor, because the choice is a property of the screen and not of any one
 * group — and a parameter is a thing twenty-two call sites can disagree about.
 */
val LocalBoxedGroups = staticCompositionLocalOf { false }

/**
 * A heading and its rows: the unit a page is built out of.
 *
 * Every settings page and every editor tab is a stack of these, which is what
 * makes them the same screen. The two differ in one thing only — whether the
 * heading sits on the page above the card or inside it — and that is decided
 * once by [LocalBoxedGroups] rather than per group.
 */
@Composable
fun PageGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.panel

    if (LocalBoxedGroups.current) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GROUP_TOP.dp, bottom = GROUP_BOTTOM.dp)
                .clip(shape)
                .background(colors.surface.copy(alpha = BOXED_GROUP_TINT), shape)
                .border(1.dp, colors.outline.copy(alpha = PANEL_BORDER_ALPHA), shape),
        ) {
            CardHeading(title = title, description = description)
            /*
             * The rows sit on their own surface inside the group, inset from
             * it. Without that the heading and the first row share one field
             * and the heading reads as the first row's title.
             */
            Box(modifier = Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) {
                GroupCard(content = content)
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = GROUP_TOP.dp, bottom = GROUP_BOTTOM.dp),
        ) {
            GroupHeading(title = title, description = description, trailing = trailing)
            GroupCard(content = content)
        }
    }
}

/**
 * One row's surface: its fill, its hairline, and its focus behaviour.
 *
 * A row is lit by focus *or* by the pointer. Answering only to focus is a
 * difference you find with a mouse plugged in and nowhere else, which is the
 * worst kind of difference — invisible until somebody attaches hardware.
 *
 * The fill is opaque so the lit state actually covers the row, and because a
 * translucent fill over a card over a wallpaper compounds into a colour nobody
 * chose.
 */
@Composable
fun RowSurface(
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = ThorTheme.colors
    val hover = rememberPointerHover()
    val lit = focused || (onClick != null && hover.isHovered)

    /*
     * Claims A while this row holds the cursor.
     *
     * Not redundant with the `clickable` below. The row's focus target is the
     * caller's `focusable`, which sits *outside* this node in the modifier
     * chain, and a synthetic DPAD_CENTER dispatched from there is not reliably
     * read as a click down here. This is what actually makes the pad work on a
     * settings page, and it was on the card these rows used to be built from.
     */
    if (onClick != null) {
        ActivateOnConfirm(focused = focused, onActivate = onClick)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Asks the nearest scrollable ancestor to reveal this row when it
            // takes the cursor. Without it, walking down a settings pane with
            // the d-pad moves the highlight off the bottom and the list stays put.
            .revealWhenFocused(focused)
            .background(if (lit) colors.surfaceHighest else colors.surfaceElevated)
            .topHairline(colors.outline.copy(alpha = PANEL_DIVIDER_ALPHA))
            .then(if (onClick != null) Modifier.pointerHover(hover) else Modifier)
            .thorCursor(focused = lit, shape = ThorTheme.shapes.small)
            .let { row ->
                if (onClick != null) row.clickable(role = Role.Button, onClick = onClick) else row
            },
        content = content,
    )
}

/** A non-row child of a group — a button, a gallery — separated like a row. */
@Composable
fun GroupBlock(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = ThorTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated)
            .topHairline(colors.outline.copy(alpha = PANEL_DIVIDER_ALPHA)),
        content = content,
    )
}

// ---- Rows -------------------------------------------------------------------

/**
 * A group of rows under a heading, on the panels.
 *
 * The panel version of [PageGroup]: the heading is inside the card rather than
 * above it, because on a 675-unit panel a heading outside the card costs a line
 * of height the panel does not have, and the badge on the end has nothing to
 * align to.
 */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    badge: String,
    badgeDot: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    PanelCard {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = dimens.spacingSmall,
                        end = dimens.spacingSmall,
                        top = dimens.spacingSmall,
                        bottom = 6.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(PANEL_HEADING_BAR.dp)
                        .clip(ThorTheme.shapes.small)
                        .background(colors.primary),
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(PANEL_HEADING_ICON.dp),
                )
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                    modifier = Modifier.weight(1f),
                )
                PanelBadge(text = badge, dot = badgeDot)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .clip(ThorTheme.shapes.panel)
                    .background(colors.surface),
                content = content,
            )
        }
    }
}

/** The pill on the end of a section heading. */
@Composable
fun PanelBadge(text: String, dot: Boolean) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small

    Row(
        modifier = Modifier
            .clip(shape)
            .background(colors.surfaceHighest, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(PANEL_DOT.dp)
                    .clip(CircleShape)
                    .background(IconTints.READY),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (dot) colors.onSurfaceVariant else colors.primary,
        )
    }
}

/**
 * A switch, in the shape everything else on these pages is.
 *
 * This replaces a green ON / red OFF pill, and the pill's argument was a real
 * one: a switch tells you which side the thumb is on and leaves you to remember
 * which side means yes, which across a room and in a list of twenty is a puzzle
 * per row. Two things answer it. The track takes the accent colour when on and
 * goes flat and grey when off, so the state is a colour difference and not only
 * a position; and every switch on a page points the same way, so one that is on
 * is visibly odd rather than needing to be read.
 *
 * What it buys is that the page stops being a column of traffic lights. A
 * saturated block was louder than the title of the row it belonged to.
 */
@Composable
fun WemuToggle(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focused: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = ThorTheme.colors
    val motion = ThorTheme.motion
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    val track by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceHighest.copy(alpha = TOGGLE_DISABLED_ALPHA)
            checked -> colors.primary
            else -> colors.surfaceHighest
        },
        animationSpec = motion.tweenSpec(motion.selectionMillis),
        label = "toggleTrack",
    )
    val offset by animateDpAsState(
        targetValue = if (checked) (TOGGLE_WIDTH - TOGGLE_THUMB - TOGGLE_PAD).dp else TOGGLE_PAD.dp,
        animationSpec = motion.tweenSpec(motion.selectionMillis),
        label = "toggleThumb",
    )

    Box(
        modifier = modifier
            .size(width = TOGGLE_WIDTH.dp, height = TOGGLE_HEIGHT.dp)
            .clip(CircleShape)
            .background(track, CircleShape)
            .border(
                width = 1.dp,
                color = if (checked) {
                    colors.primary
                } else {
                    colors.outline.copy(alpha = if (lit) 0.7f else PANEL_BORDER_ALPHA)
                },
                shape = CircleShape,
            )
            .then(if (onClick != null) Modifier.pointerHover(hover) else Modifier)
            .thorCursor(focused = lit, shape = CircleShape)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .let { t ->
                if (onClick != null && enabled) {
                    t.clickable(role = Role.Switch, onClick = onClick)
                } else {
                    t
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = offset)
                .size(TOGGLE_THUMB.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) Color.White else Color.White.copy(alpha = TOGGLE_DISABLED_ALPHA),
                    CircleShape,
                ),
        )
    }
}

/**
 * The stated value on the end of a row that opens a menu.
 *
 * Neutral rather than accent-tinted. It was in the cursor colour, which on a
 * page of eight of them made the *values* the brightest thing on screen — and
 * the value is the thing you already know, because it is what is currently set.
 * The accent belongs on the heading and the focus ring.
 */
@Composable
fun ValuePill(
    text: String,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    focused: Boolean = false,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    Row(
        modifier = modifier
            .height(PILL_HEIGHT.dp)
            .widthIn(min = PILL_MIN_WIDTH.dp)
            .pointerHover(hover)
            .clip(shape)
            .background(if (lit) colors.surfaceHighest else colors.surface, shape)
            .border(
                width = 1.dp,
                color = if (lit) {
                    colors.cursor.copy(alpha = 0.7f)
                } else {
                    colors.outline.copy(alpha = PANEL_BORDER_ALPHA)
                },
                shape = shape,
            )
            .padding(horizontal = PILL_PAD.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) colors.onBackground else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(PILL_ICON.dp),
            )
        }
    }
}

/**
 * A small chip stating a fact about the row it sits on: Stable, Beta,
 * Installed.
 *
 * Coloured by what it says rather than by the theme, so "Beta" is the same
 * colour in the component list as it is anywhere else it turns up.
 */
@Composable
fun StatusChip(text: String, tint: Color, modifier: Modifier = Modifier) {
    val shape = ThorTheme.shapes.small

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = CHIP_FILL_ALPHA), shape)
            .border(1.dp, tint.copy(alpha = CHIP_BORDER_ALPHA), shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * One setting: what it is, what it does, what it is set to, and a way in.
 *
 * The subtitle is the part that earns its line. "Emulator: FEXCore" means
 * nothing to somebody who has not read a Winlator thread; "System call
 * translation layer" is the sentence that makes the setting above it legible,
 * and it costs one line of small grey text.
 */
@Composable
fun DetailRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    value: String,
    /**
     * Where the row goes, or null for a fact that is not a setting.
     *
     * Null takes the chevron away as well as the press, which is the part that
     * matters: a chevron is a promise, and a game's install path is genuinely
     * somewhere you cannot be taken. Everything else about the row is identical,
     * so the two kinds still line up down the panel.
     */
    onClick: (() -> Unit)? = null,
    /**
     * Puts the value on its own line under the subtitle.
     *
     * For a value that *is* the content of the row rather than a setting's
     * current state. A container's rows read "Resolution … 1920x1080" and fit
     * beside their titles; a game's read "Location … F:\Games\Assassins Creed
     * II\AssassinsCreedII.exe", which in the same place clips to
     * "F:\Games\Assassi…" — and a path clipped in the middle answers nothing.
     */
    valueBelow: Boolean = false,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.small

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (lit) colors.surfaceElevated else Color.Transparent, shape)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier
                        .pointerHover(hover)
                        .thorCursor(focused = lit, shape = shape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                        .focusable(interactionSource = interactionSource)
                },
            )
            .padding(horizontal = dimens.spacingSmall, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        IconTile(icon = icon, tint = tint)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (valueBelow) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                    // Two lines is enough for any path this app produces, and
                    // the tail is the part that identifies it — so what gets
                    // dropped, when anything does, is the middle.
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (!valueBelow) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Capped rather than wrapped: a Wine identifier is long enough
                // to push the title off its own row, and the full value is on
                // the tab this row opens.
                modifier = Modifier.widthIn(max = PANEL_VALUE_MAX.dp),
            )
        }

        if (onClick != null) Chevron()
    }
}

/**
 * The wide row: an action rather than a setting.
 *
 * Same shape as a [DetailRow] with the value taken out, because it is the same
 * gesture — press the row, go somewhere — and giving it a button of its own
 * would say it behaves differently.
 */
@Composable
fun LinkRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (lit) colors.surfaceHighest else colors.surfaceElevated, shape)
            .border(1.dp, colors.outline.copy(alpha = PANEL_BORDER_ALPHA), shape)
            .pointerHover(hover)
            .thorCursor(focused = lit, shape = shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .padding(dimens.spacingSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        IconTile(icon = icon, tint = tint)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Chevron()
    }
}

/** One of the three shortcuts along the foot of a section. */
data class QuickAction(
    val icon: ImageVector,
    val tint: Color,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
)

/**
 * Three shortcuts across the bottom of a section.
 *
 * Divided rather than spaced. They are three separate destinations at a size
 * where the icon of one sits close to the text of the next, and without a rule
 * between them the row reads as one sentence.
 */
@Composable
fun QuickActions(vararg actions: QuickAction) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(PANEL_QUICK_DIVIDER.dp)
                        .background(ThorTheme.colors.outline.copy(alpha = PANEL_DIVIDER_ALPHA)),
                )
            }
            QuickActionCell(action = action, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun QuickActionCell(action: QuickAction, modifier: Modifier = Modifier) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    Row(
        modifier = modifier
            .clip(shape)
            .background(if (lit) colors.surfaceElevated else Color.Transparent, shape)
            .pointerHover(hover)
            .thorCursor(focused = lit, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = action.onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = action.tint,
            modifier = Modifier.size(PANEL_QUICK_ICON.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = action.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Chevron()
    }
}

// ---- Parts ------------------------------------------------------------------

/**
 * A row's icon, on a tint of its own.
 *
 * The tile is what makes a colour readable at this size: a 14dp glyph in
 * saturated green on a dark panel is a smudge, and the same glyph on a wash of
 * the same green is a green thing. It also keeps the rows aligned, which a bare
 * icon with its own aspect ratio does not.
 */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    val shape = ThorTheme.shapes.small

    Box(
        modifier = modifier
            .size(PANEL_TILE.dp)
            .clip(shape)
            .background(tint.copy(alpha = PANEL_TILE_FILL_ALPHA), shape)
            .border(1.dp, tint.copy(alpha = PANEL_TILE_BORDER_ALPHA), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(PANEL_TILE_ICON.dp),
        )
    }
}

/**
 * A tile you can press: the back arrow, a refresh, an overflow.
 *
 * The same object as an [IconTile] so a header of mixed decoration and controls
 * still lines up, but in the accent colour and lit by focus — which is the
 * whole of what says one is pressable and the other is not.
 */
@Composable
fun IconButtonTile(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.small
    val accent = tint ?: colors.cursor

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    Box(
        modifier = modifier
            .size(PANEL_BUTTON_TILE.dp)
            .clip(shape)
            .background(
                accent.copy(alpha = if (lit) PANEL_TILE_LIT_ALPHA else PANEL_TILE_FILL_ALPHA),
                shape,
            )
            .border(1.dp, accent.copy(alpha = PANEL_TILE_BORDER_ALPHA), shape)
            .pointerHover(hover)
            .thorCursor(focused = lit, shape = shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(PANEL_TILE_ICON.dp),
        )
    }
}

@Composable
fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = null,
        tint = ThorTheme.colors.onSurfaceVariant,
        modifier = Modifier.size(PANEL_CHEVRON.dp),
    )
}

@Composable
fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ThorTheme.dimens.spacingSmall)
            .height(1.dp)
            .background(ThorTheme.colors.outline.copy(alpha = PANEL_DIVIDER_ALPHA)),
    )
}

/** The two big ones under the hero: start it, or set it up. */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.panel

    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val hover = rememberPointerHover()
    val lit = focused || hover.isHovered

    Row(
        modifier = modifier
            .height(PANEL_BIG_BUTTON.dp)
            .clip(shape)
            .then(
                if (primary) {
                    Modifier.background(Brush.linearGradient(colors.accentStops), shape)
                } else {
                    Modifier
                        .background(colors.surfaceElevated, shape)
                        .border(1.dp, colors.outline.copy(alpha = PANEL_BORDER_ALPHA), shape)
                },
            )
            .pointerHover(hover)
            .thorCursor(focused = lit, shape = shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (primary) Color.White else colors.onBackground,
            modifier = Modifier.size(PANEL_BIG_ICON.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color.White else colors.onBackground,
        )
    }
}

/** The panel's own surface, used for every group on one. */
@Composable
fun PanelCard(
    tint: Color? = null,
    content: @Composable () -> Unit,
) {
    val colors = ThorTheme.colors
    val shape = ThorTheme.shapes.panel

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint ?: colors.surfaceElevated, shape)
            .border(1.dp, colors.outline.copy(alpha = PANEL_BORDER_ALPHA), shape),
    ) {
        content()
    }
}

/**
 * A colour per kind of thing, resolved through whatever theme is loaded.
 *
 * Each name here is an alias for one of the twelve [AccentSlot]s, and the theme
 * decides what a slot actually looks like — see `ThemeHarmony`. A monochrome
 * theme renders all twelve as one hue separated by lightness; a spectrum theme
 * renders them as twelve distinct colours; a triadic theme groups them into
 * three families. Nothing here has to know which.
 *
 * This used to be twenty-nine fixed pastels, on the argument that a fixed colour
 * is what lets the driver row be *the green one* every time. That argument
 * survives — the slot a name points at never changes, so the driver row is
 * always the same slot, and under most themes that is still green. What did not
 * survive is the implementation: those pastels were picked against a dark panel,
 * and the launcher has light themes, on which every icon in the app was a pale
 * wash on near-white. Generating them against the resolved ground fixes that,
 * and is what a "themeable" colour has to mean if it is to mean anything.
 *
 * Grouped by subject rather than sorted, because that is how a new one is
 * chosen: anything to do with the screen is blue, anything to do with speed is
 * orange, anything destructive is red. A new row picks its neighbours' slot.
 */
object IconTints {
    // Display and layout.
    val RESOLUTION: Color @Composable get() = tint(AccentSlot.BLUE)
    val DISPLAY: Color @Composable get() = tint(AccentSlot.CYAN)
    val LAYOUT: Color @Composable get() = tint(AccentSlot.INDIGO)

    // The Windows side.
    val WINE: Color @Composable get() = tint(AccentSlot.MAGENTA)
    val EMULATOR: Color @Composable get() = tint(AccentSlot.VIOLET)
    val COMPONENTS: Color @Composable get() = tint(AccentSlot.CYAN)
    val SERVICES: Color @Composable get() = tint(AccentSlot.INDIGO)

    // Graphics.
    val DRIVER: Color @Composable get() = tint(AccentSlot.GREEN)
    val DIRECT3D: Color @Composable get() = tint(AccentSlot.INDIGO)
    val SHADERS: Color @Composable get() = tint(AccentSlot.TEAL)

    // The rest of the machine.
    val AUDIO: Color @Composable get() = tint(AccentSlot.TEAL)
    val PERFORMANCE: Color @Composable get() = tint(AccentSlot.ORANGE)
    val MEMORY: Color @Composable get() = tint(AccentSlot.AMBER)
    val INPUT: Color @Composable get() = tint(AccentSlot.RED)
    val FILES: Color @Composable get() = tint(AccentSlot.AMBER)
    val ADVANCED: Color @Composable get() = tint(AccentSlot.SLATE)
    val NETWORK: Color @Composable get() = tint(AccentSlot.CYAN)

    // Appearance.
    val THEME: Color @Composable get() = tint(AccentSlot.VIOLET)
    val COLOUR: Color @Composable get() = tint(AccentSlot.MAGENTA)
    val TYPE: Color @Composable get() = tint(AccentSlot.BLUE)
    val MOTION: Color @Composable get() = tint(AccentSlot.AMBER)
    val ACCESSIBILITY: Color @Composable get() = tint(AccentSlot.TEAL)

    /*
     * The game panel's own, continuing the same spread.
     *
     * Reusing a container slot for a different kind of thing would be worse than
     * having no colour at all — the point of the scheme is that a tint means one
     * thing, and both panels are on the same screen a moment apart.
     */
    val PROGRAM: Color @Composable get() = tint(AccentSlot.CYAN)
    val LOCATION: Color @Composable get() = tint(AccentSlot.AMBER)
    val CONTAINER: Color @Composable get() = tint(AccentSlot.VIOLET)
    val ARGUMENTS: Color @Composable get() = tint(AccentSlot.SLATE)
    val RENAME: Color @Composable get() = tint(AccentSlot.BLUE)
    val REMOVE: Color @Composable get() = tint(AccentSlot.RED)

    /**
     * States, rather than subjects.
     *
     * [READY] is the one colour in the app that does *not* go through the theme.
     * Green means "this worked" and a theme does not get an opinion about that —
     * a monochrome theme would otherwise report success in whatever colour it
     * reports everything else in, which is not a report.
     */
    val READY = Color(0xFF22C55E)
    val BETA: Color @Composable get() = tint(AccentSlot.AMBER)
    val INFO: Color @Composable get() = tint(AccentSlot.BLUE)

    @Composable
    private fun tint(slot: AccentSlot): Color = ThorTheme.colors.tint(slot)
}

// ---- Measurements -----------------------------------------------------------

internal const val PANEL_BIG_BUTTON = 44
internal const val PANEL_BIG_ICON = 18
internal const val PANEL_HEADING_BAR = 15
internal const val PANEL_HEADING_ICON = 15
internal const val PANEL_TILE = 28
internal const val PANEL_TILE_ICON = 16
internal const val PANEL_BUTTON_TILE = 32
internal const val PANEL_TILE_FILL_ALPHA = 0.15f
internal const val PANEL_TILE_BORDER_ALPHA = 0.3f
internal const val PANEL_TILE_LIT_ALPHA = 0.3f
internal const val PANEL_CHEVRON = 16
internal const val PANEL_QUICK_ICON = 15
internal const val PANEL_QUICK_DIVIDER = 26
internal const val PANEL_DOT = 6

/**
 * How wide a value is allowed to be before it is clipped.
 *
 * A Wine identifier — `Proton-11.0-3-arm64ec-4` — is wider than the label it
 * sits beside, and left unbounded it pushed "Resolution" and its explanation
 * into two lines each.
 */
internal const val PANEL_VALUE_MAX = 116

internal const val PANEL_BORDER_ALPHA = 0.45f
internal const val PANEL_DIVIDER_ALPHA = 0.35f

/** The square beside a panel's title, sized to the headline next to it. */
internal const val HERO_MARK = 52

/** How much red washes the card behind an irreversible question. */
internal const val WARNING_ALPHA = 0.14f

/** The hero band. */
private const val HERO_PAD = 12
private const val HERO_TILE = 46
private const val HERO_TILE_ICON = 24
private const val HERO_TILE_FILL = 0.18f
private const val HERO_TILE_BORDER = 0.34f
private const val HERO_WASH = 0.10f

/** The decorative cluster: big, faint, and stepped so it reads as one object. */
private const val HERO_ART_MAX = 3
private const val HERO_ART_BASE = 34
private const val HERO_ART_STEP = 13
private const val HERO_ART_RISE = 3
private const val HERO_ART_OVERLAP = 6
private const val HERO_ART_ALPHA = 0.10f
private const val HERO_ART_ALPHA_STEP = 0.035f
private const val HERO_ART_INSET = 18

/** Width kept clear on the right of a hero, so the title never runs under the art. */
private const val HERO_ART_RESERVE = 92

/** Group headings, and the space a group owns. */
private const val HEADING_GAP = 7
private const val HEADING_INSET = 4
private const val HEADING_BAR_GAP = 8
private const val HEADING_BAR_WIDTH = 3
private const val HEADING_BAR_SHORT = 13
private const val HEADING_BAR_TALL = 30
private const val HEADING_TRACKING = 0.9f
private const val GROUP_TOP = 10
private const val GROUP_BOTTOM = 4

/** How far the editor's outer group sits below the panel it is drawn on. */
private const val BOXED_GROUP_TINT = 0.5f

private const val CHIP_FILL_ALPHA = 0.14f
private const val CHIP_BORDER_ALPHA = 0.34f

/** The switch. Wider than it is tall by enough that the travel is obvious. */
private const val TOGGLE_WIDTH = 42
private const val TOGGLE_HEIGHT = 24
private const val TOGGLE_THUMB = 18
private const val TOGGLE_PAD = 2
private const val TOGGLE_DISABLED_ALPHA = 0.4f

/** The value pill on the end of a row that opens a menu. */
private const val PILL_HEIGHT = 30
private const val PILL_MIN_WIDTH = 120
private const val PILL_PAD = 10
private const val PILL_ICON = 16
