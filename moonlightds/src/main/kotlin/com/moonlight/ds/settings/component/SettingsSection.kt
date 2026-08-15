package com.moonlight.ds.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thor.core.designsystem.modifier.thorCursor
import com.thor.core.designsystem.theme.ThorTheme
import com.moonlight.ds.settings.component.panel.GroupCard
import com.moonlight.ds.settings.component.panel.GroupHeading
import com.moonlight.ds.settings.component.panel.RowSurface
import com.thor.core.ui.pointer.pointerHover
import com.thor.core.ui.pointer.rememberPointerHover

/**
 * A titled group of related settings.
 *
 * Deliberately light: a heading, generous breathing room and hairline
 * separators between rows. An earlier version boxed every group in a bordered,
 * tinted card, which at six or seven groups per pane turned the screen into a
 * stack of competing containers.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dimens = ThorTheme.dimens

    /*
     * A heading, then one card holding every row in the group.
     *
     * This drew each row as its own rounded, bordered card with a gap between —
     * which reads as a stack of separate objects rather than as a group, and
     * puts two borders and two corner radii between neighbouring settings that
     * belong together. One card with hairlines inside it is the same
     * information with an order of magnitude less furniture.
     */
    Column(modifier = modifier.padding(top = dimens.spacingSmall, bottom = dimens.spacing)) {
        GroupHeading(title = title, description = description)
        GroupCard(content = content)
    }
}

/**
 * Kept as a no-op, and called from every page.
 *
 * Rows draw their own hairline now — see `RowSurface` — so a spacer here would
 * reopen the gaps the group card exists to close. Left in place rather than
 * deleted because it is called several hundred times across seventeen page
 * files, and removing it would be a mechanical edit with no visible result.
 */
@Composable
fun RowDivider() = Unit

/**
 * The surface every settings row sits on.
 *
 * Delegates rather than reimplementing, so a row here and a row on any other
 * panel are the same object: flat, edge to edge, and separated from its
 * neighbour by a hairline it draws itself rather than by a border and a gap.
 */
@Composable
fun SettingsCard(
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = RowSurface(focused = focused, modifier = modifier, onClick = onClick, content = content)

/** A section heading used outside a [SettingsSection]. */
@Composable
fun SectionHeader(text: String) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    Row(
        modifier = Modifier.padding(top = dimens.spacingLarge, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 18.dp)
                .clip(ThorTheme.shapes.pill)
                .background(colors.cursor),
        )
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = colors.cursor,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}
