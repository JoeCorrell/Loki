package com.thor.feature.settings.component.row

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.feature.settings.component.SettingsCard
import com.thor.feature.settings.component.panel.IconTile

/**
 * Shared row shell.
 *
 * A tile, a title and a description on the left, one control on the right. Every
 * settings row uses this, so a pane reads as a single column of labels with a
 * single column of controls rather than a mix of inline widgets at varying
 * heights — and so a change of look here is a change of look everywhere, which
 * is how the whole of Settings was restyled without editing twenty page files.
 *
 * @param icon the glyph in the row's tile. Null falls back to a plain marker
 *   rather than to a gap: rows are adopting icons page by page, and a row that
 *   simply lost its left edge while its neighbours kept theirs would read as
 *   broken alignment rather than as an absent decoration.
 */
@Composable
internal fun SettingsRowShell(
    title: String,
    subtitle: String?,
    focused: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleColor: Color? = null,
    icon: ImageVector? = null,
    semanticRole: Role? = null,
    semanticState: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    SettingsCard(
        focused = focused,
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = ROW_HEIGHT.dp)
            .semantics(mergeDescendants = true) {
                if (onClick != null) role = semanticRole ?: Role.Button
                semanticState?.let { stateDescription = it }
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ROW_INSET.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            /*
             * The tile lights with the cursor rather than staying inert.
             *
             * It is the largest coloured object in the row, so leaving it fixed
             * while a ring appeared around the card meant the focused row and its
             * neighbours differed only at their edges — which is exactly the
             * difference that disappears across a room.
             */
            val tint = if (focused) colors.cursor else colors.onSurfaceVariant

            if (icon != null) {
                IconTile(icon = icon, tint = tint)
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = if (subtitle == null) 26.dp else 38.dp)
                        .clip(ThorTheme.shapes.pill)
                        .background(
                            if (focused) colors.cursor else colors.outline.copy(alpha = 0.34f),
                        ),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor ?: colors.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
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

private const val ROW_HEIGHT = 60

/** Inset that keeps the focus ring clear of the row's content. */
internal const val ROW_INSET = 14
internal const val VALUE_MAX_WIDTH = 200
