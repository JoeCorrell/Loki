package com.moonlight.ds.settings.component.panel

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thor.core.designsystem.modifier.SurfaceLevel
import com.moonlight.ds.settings.component.revealWhenFocused
import com.thor.core.designsystem.modifier.thorCursor
import com.thor.core.designsystem.modifier.thorSurface
import com.thor.core.ui.pointer.pointerHover
import com.thor.core.ui.pointer.rememberPointerHover
import com.thor.core.designsystem.theme.ThorTheme
import com.moonlight.ds.settings.component.row.ActivateOnConfirm

/**
 * The app's panel.
 *
 * Everything with a surface under it goes through here, so a theme change or a
 * corner-style change reaches all of it at once.
 */
@Composable
fun WemuPanel(
    modifier: Modifier = Modifier,
    level: SurfaceLevel = SurfaceLevel.RAISED,
    color: Color = ThorTheme.colors.surface,
    bordered: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(ThorTheme.dimens.spacing),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(ThorTheme.dimens.spacingSmall),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .thorSurface(
                shape = ThorTheme.shapes.panel,
                color = color,
                level = level,
                bordered = bordered,
            )
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/**
 * A titled group of settings.
 *
 * A thin name over [PageGroup], which is where the shape now lives, because a
 * group of settings and a group of anything else are the same object and there
 * are a hundred and thirty call sites that say `WemuSection`.
 *
 * The shape changed once and is worth recording, because the previous version of
 * this comment argued the opposite case. It used to be a tinted panel holding a
 * stack of individually bordered, individually rounded cards separated by air.
 * That reads as *n* things under a label rather than as one labelled thing, and
 * on a page of four groups it produced twenty-odd competing rectangles.
 *
 * Now: the heading is in the accent colour above the group, and the rows are
 * one flush surface divided by hairlines. Same information, one object per
 * heading.
 */
@Composable
fun WemuSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    level: SurfaceLevel = SurfaceLevel.RAISED,
    /** A control belonging to the heading rather than to any one row. */
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageGroup(
        title = title,
        modifier = modifier,
        description = subtitle,
        trailing = trailing,
        content = content,
    )
}

/**
 * The heading of a group, without the group.
 *
 * For the two places that have a heading over something which is not a column
 * of rows — the theme gallery, which scrolls sideways, and the artwork grid.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    GroupHeading(title = title, modifier = modifier, description = subtitle)
}

/*
 * `WemuCard` used to live here: a bordered, rounded, individually lit card, one
 * per settings row. It is gone rather than deprecated because a primitive that
 * still compiles is a primitive somebody reaches for, and this one would put a
 * card back inside a group that has already drawn one.
 *
 * What replaced it is `RowSurface` in Panels.kt, which carries the same two
 * behaviours that were easy to miss and expensive to lose: `revealWhenFocused`,
 * so walking a long page with the d-pad scrolls it, and `OnFocusedControllerClick`,
 * so A presses the focused row.
 */

/**
 * An icon tile, a title, a description, one control on the right.
 *
 * Every settings row in the app goes through this, so a page reads as a single
 * column of labelled things beside a single column of controls rather than as a
 * mix of inline widgets at varying heights.
 *
 * The icon is the change worth explaining. A settings page is scanned, not read
 * — you arrive knowing what you came for and want to find that one row — and a
 * column of identical grey text is the worst possible surface for that, because
 * every row costs a read. A tinted tile per row turns the scan into a colour
 * match, which is roughly free.
 *
 * It defaults to null and draws the old pill in that case, so the several dozen
 * rows in the console and the Steam screens that have not been given one yet
 * still line up with the ones that have.
 */
@Composable
fun WemuRowShell(
    title: String,
    subtitle: String?,
    focused: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleColor: Color? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = IconTints.ADVANCED,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    RowSurface(
        focused = focused,
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ROW_HEIGHT.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ROW_INSET.dp, vertical = ROW_PAD.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
        ) {
            if (icon != null) {
                IconTile(icon = icon, tint = if (enabled) tint else colors.onSurfaceVariant)
            } else {
                Box(
                    modifier = Modifier
                        .size(
                            width = ROW_PILL_WIDTH.dp,
                            height = if (subtitle == null) ROW_PILL_SHORT.dp else ROW_PILL_TALL.dp,
                        )
                        .clip(ThorTheme.shapes.pill)
                        .background(
                            if (focused) {
                                colors.cursor
                            } else {
                                colors.outline.copy(alpha = ROW_PILL_ALPHA)
                            },
                        ),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = titleColor ?: if (enabled) colors.onBackground else colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/**
 * A label and its value, side by side.
 *
 * The workhorse of the console: nearly everything the engine exposes is one name
 * and one reading. Drawn on the same surface as every other row so a stats page
 * and a settings page are visibly the same interface.
 */
@Composable
fun WemuStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = ThorTheme.colors.onSurface,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = IconTints.ADVANCED,
) {
    WemuRowShell(
        title = label,
        subtitle = subtitle,
        focused = false,
        onClick = null,
        modifier = modifier,
        icon = icon,
        tint = tint,
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = STAT_VALUE_MAX.dp),
            )
        },
    )
}

/** Focus state for a row, for callers that build their own shell. */
@Composable
fun rememberRowInteraction(): Pair<MutableInteractionSource, Boolean> {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return source to focused
}

/** Makes an arbitrary composable focusable in the same way a row is. */
fun Modifier.rowFocusable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Modifier = focusable(enabled = enabled, interactionSource = interactionSource)

// ---- Measurements ----------------------------------------------------------

/**
 * Shorter than it was, and deliberately.
 *
 * A row used to be 60dp with 14dp of padding on every side because it was a
 * card and needed clearance from its neighbours. Inside a divided group it has
 * none of them to clear, and the height it was spending on that is height a
 * page of a dozen settings cannot afford — Graphics is fourteen rows and used
 * to be three screens of scrolling.
 */
private const val ROW_HEIGHT = 46
private const val ROW_INSET = 12
private const val ROW_PAD = 8

/** The bar drawn in place of an icon, for a row that has not been given one. */
private const val ROW_PILL_WIDTH = 3
private const val ROW_PILL_SHORT = 22
private const val ROW_PILL_TALL = 32

private const val ROW_PILL_ALPHA = 0.34f

/** Long enough for a resolution or a version, short enough to leave the title room. */
private const val STAT_VALUE_MAX = 200
