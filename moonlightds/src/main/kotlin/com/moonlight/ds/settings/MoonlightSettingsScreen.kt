@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.moonlight.ds.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moonlight.ds.settings.component.panel.GroupCard
import com.moonlight.ds.settings.component.panel.PageHero
import com.moonlight.ds.settings.component.row.LocalHorizontalRowRegistration
import com.moonlight.ds.settings.component.row.LocalRowActivation
import com.moonlight.ds.settings.component.row.LocalRowStep
import com.moonlight.ds.settings.page.CONTROLLER_ROWS
import com.moonlight.ds.settings.page.ControllerPage
import com.moonlight.ds.settings.page.DISPLAY_ROWS
import com.moonlight.ds.settings.page.DisplayPage
import com.moonlight.ds.settings.page.STREAM_AUDIO_ROWS
import com.moonlight.ds.settings.page.STREAM_HOSTS_ROWS
import com.moonlight.ds.settings.page.STREAM_NETWORK_ROWS
import com.moonlight.ds.settings.page.STREAM_POINTER_ROWS
import com.moonlight.ds.settings.page.STREAM_SECOND_SCREEN_ROWS
import com.moonlight.ds.settings.page.STREAM_SESSION_ROWS
import com.moonlight.ds.settings.page.STREAM_VIDEO_ROWS
import com.moonlight.ds.settings.page.StreamControlsSection
import com.moonlight.ds.settings.page.StreamControlsPage
import com.moonlight.ds.settings.page.StreamHostsPage
import com.moonlight.ds.settings.page.StreamQualitySection
import com.moonlight.ds.settings.page.StreamQualityPage
import com.thor.core.designsystem.component.GlassSurface
import com.thor.core.designsystem.modifier.SurfaceLevel
import com.thor.core.designsystem.modifier.thorCursor
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.model.AccentSlot
import com.thor.core.model.ControllerCommand
import com.thor.core.model.ThorSettings

/**
 * One page of settings.
 *
 * The app's focused settings categories. Large mixed pages are split by purpose
 * so the rail is useful as a quick index rather than merely occupying one side.
 */
internal enum class SettingsPage(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val rows: Int,
    val slot: AccentSlot,
) {
    VIDEO(
        title = "Video",
        summary = "Resolution, FPS and codec",
        icon = Icons.Rounded.HighQuality,
        rows = STREAM_VIDEO_ROWS,
        slot = AccentSlot.CYAN,
    ),
    AUDIO(
        title = "Audio",
        summary = "Stream sound",
        icon = Icons.AutoMirrored.Rounded.VolumeUp,
        rows = STREAM_AUDIO_ROWS,
        slot = AccentSlot.MAGENTA,
    ),
    NETWORK(
        title = "Network",
        summary = "Connection tuning",
        icon = Icons.Rounded.Wifi,
        rows = STREAM_NETWORK_ROWS,
        slot = AccentSlot.TEAL,
    ),
    SECOND_SCREEN(
        title = "Second screen",
        summary = "Dual-monitor streaming",
        icon = Icons.Rounded.Monitor,
        rows = STREAM_SECOND_SCREEN_ROWS,
        slot = AccentSlot.INDIGO,
    ),
    POINTER(
        title = "Pointer",
        summary = "Touch and mouse",
        icon = Icons.Rounded.TouchApp,
        rows = STREAM_POINTER_ROWS,
        slot = AccentSlot.BLUE,
    ),
    COMPUTERS(
        title = "PCs",
        summary = "Discovery and identity",
        icon = Icons.Rounded.Computer,
        rows = STREAM_HOSTS_ROWS,
        slot = AccentSlot.GREEN,
    ),
    DISPLAY(
        title = "Screens",
        summary = "Panel layout",
        icon = Icons.Rounded.ScreenRotation,
        rows = DISPLAY_ROWS,
        slot = AccentSlot.ORANGE,
    ),
    CONTROLLER(
        title = "Controller",
        summary = "Pad and feedback",
        icon = Icons.Rounded.Gamepad,
        rows = CONTROLLER_ROWS,
        slot = AccentSlot.RED,
    ),
    SESSION(
        title = "Session",
        summary = "Shortcuts and exit",
        icon = Icons.AutoMirrored.Rounded.ExitToApp,
        rows = STREAM_SESSION_ROWS,
        slot = AccentSlot.AMBER,
    ),
}

/**
 * Where the controller is inside the settings screen.
 *
 * Two zones rather than one flat index, because the rail and the page scroll
 * independently: Left from the first column of a page returns to the rail, and
 * Right from the rail enters the page at the row it was left on.
 */
@Immutable
internal data class SettingsCursor(
    val page: SettingsPage = SettingsPage.VIDEO,
    val onRail: Boolean = true,
    val row: Int = 0,
)

/**
 * Drives the settings cursor from the controller.
 *
 * Held outside the composable so the shell can route commands into it without
 * the screen having to be composed first — the same arrangement Loki uses, and
 * the reason a settings page can be opened by a button rather than only by a tap.
 */
internal class SettingsController {

    var cursor by mutableStateOf(SettingsCursor())
        private set

    /** Incremented on Confirm; see [LocalRowActivation]. */
    var activation by mutableIntStateOf(0)
        private set

    /** A running signed count of horizontal steps; see [LocalRowStep]. */
    var step by mutableIntStateOf(0)
        private set

    /**
     * Whether the focused row wants Left and Right for itself.
     *
     * Set by the row through [LocalHorizontalRowRegistration]. Without it, Left
     * on a dropdown would leave the page instead of changing the value.
     */
    private var horizontalRowFocused by mutableStateOf(false)

    fun setRowTakesHorizontal(takes: Boolean) {
        horizontalRowFocused = takes
    }

    fun reset() {
        cursor = SettingsCursor()
    }

    /**
     * Moves to a page from a tap.
     *
     * Leaves the cursor on the rail, because a finger choosing a category has
     * said which page to show and nothing about which row it wants — where the
     * pad's Right press does say exactly that.
     */
    fun selectPage(page: SettingsPage) {
        cursor = SettingsCursor(page = page, onRail = true, row = 0)
    }

    /**
     * Applies a command, and reports whether it was consumed.
     *
     * Back is deliberately *not* consumed on the rail: that is the press that
     * closes the settings screen, and the shell above needs to see it.
     */
    fun handleCommand(command: ControllerCommand): Boolean {
        val current = cursor
        return when (command) {
            ControllerCommand.NAVIGATE_UP -> {
                cursor = if (current.onRail) {
                    val pages = SettingsPage.entries
                    val next = pages[(pages.indexOf(current.page) - 1).mod(pages.size)]
                    current.copy(page = next, row = 0)
                } else {
                    current.copy(row = (current.row - 1).coerceAtLeast(0))
                }
                true
            }

            ControllerCommand.NAVIGATE_DOWN -> {
                cursor = if (current.onRail) {
                    val pages = SettingsPage.entries
                    val next = pages[(pages.indexOf(current.page) + 1).mod(pages.size)]
                    current.copy(page = next, row = 0)
                } else {
                    current.copy(row = (current.row + 1).coerceAtMost(current.page.rows - 1))
                }
                true
            }

            ControllerCommand.NAVIGATE_RIGHT -> {
                when {
                    current.onRail -> {
                        cursor = current.copy(onRail = false, row = 0)
                        true
                    }
                    // The row owns this direction, so it becomes a value change
                    // rather than a move.
                    horizontalRowFocused -> {
                        step += 1
                        true
                    }
                    else -> true
                }
            }

            ControllerCommand.NAVIGATE_LEFT -> {
                when {
                    current.onRail -> true
                    horizontalRowFocused -> {
                        step -= 1
                        true
                    }
                    else -> {
                        cursor = current.copy(onRail = true)
                        true
                    }
                }
            }

            ControllerCommand.CONFIRM -> {
                if (current.onRail) {
                    cursor = current.copy(onRail = false, row = 0)
                } else {
                    activation += 1
                }
                true
            }

            ControllerCommand.BACK -> {
                if (current.onRail) {
                    // Left for the shell, which closes the screen.
                    false
                } else {
                    cursor = current.copy(onRail = true)
                    true
                }
            }

            else -> false
        }
    }
}

/**
 * Moonlight DS's settings.
 *
 * Every streaming value Loki exposes is here, drawn by the same row components
 * against the same `ThorSettings` document — the pages under `page/` are a copy
 * of Loki's, so a row reads and writes exactly what it does there.
 */
@Composable
internal fun MoonlightSettingsScreen(
    settings: ThorSettings,
    controller: SettingsController,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val cursor = controller.cursor
    val focusedRow = if (cursor.onRail) NO_ROW else cursor.row

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    colors = listOf(colors.surfaceElevated, colors.background),
                ),
            )
            .windowInsetsPadding(
                WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom),
            )
            .padding(dimens.spacingSmall),
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        GlassSurface(
            modifier = Modifier.width(RAIL_WIDTH.dp).fillMaxHeight(),
            shape = ThorTheme.shapes.large,
            color = colors.surface,
            alphaOverride = 0.92f,
            level = SurfaceLevel.RAISED,
            bordered = true,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = dimens.spacing),
            ) {
                Text(
                    text = "MOONLIGHT DS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.cursor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = dimens.spacingLarge),
                )
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onBackground,
                    modifier = Modifier.padding(
                        start = dimens.spacingLarge,
                        end = dimens.spacingLarge,
                        bottom = 2.dp,
                    ),
                )
                Text(
                    text = "Stream and device options",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(
                        start = dimens.spacingLarge,
                        end = dimens.spacingLarge,
                        bottom = dimens.spacing,
                    ),
                )

                BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val perRow = maxHeight / SettingsPage.entries.size
                    val compact = perRow < COMFORTABLE_CATEGORY_ROW.dp

                    Column(modifier = Modifier.fillMaxSize()) {
                        SettingsPage.entries.forEach { page ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .heightIn(max = MAX_CATEGORY_ROW.dp),
                            ) {
                                MoonlightCategoryRow(
                                    page = page,
                                    selected = cursor.page == page,
                                    cursorHere = cursor.onRail && cursor.page == page,
                                    compact = compact,
                                    onClick = { controller.selectPage(page) },
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(ThorTheme.shapes.large)
                .background(colors.surface.copy(alpha = 0.38f)),
        ) {
            SettingsDetailHeader(page = cursor.page)

            CompositionLocalProvider(
                LocalRowActivation provides controller.activation,
                LocalRowStep provides controller.step,
                LocalHorizontalRowRegistration provides controller::setRowTakesHorizontal,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = CONTENT_MAX_WIDTH.dp)
                        .fillMaxWidth()
                        .weight(1f)
                        .align(Alignment.CenterHorizontally)
                        .padding(
                            start = dimens.spacingLarge,
                            end = dimens.spacingLarge,
                            bottom = dimens.spacingLarge,
                        )
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(dimens.spacing),
                ) {
                    PageHero(
                        eyebrow = "MOONLIGHT DS",
                        title = cursor.page.title,
                        subtitle = cursor.page.summary,
                        icon = cursor.page.icon,
                        tint = colors.tint(cursor.page.slot),
                    )
                    GroupCard {
                        MoonlightSettingsPageContent(
                            page = cursor.page,
                            settings = settings,
                            focusedRow = focusedRow,
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoonlightSettingsPageContent(
    page: SettingsPage,
    settings: ThorSettings,
    focusedRow: Int,
    viewModel: SettingsViewModel,
) {
    when (page) {
        SettingsPage.VIDEO -> StreamQualityPage(
            settings,
            focusedRow,
            viewModel,
            StreamQualitySection.VIDEO,
        )
        SettingsPage.AUDIO -> StreamQualityPage(
            settings,
            focusedRow,
            viewModel,
            StreamQualitySection.AUDIO,
        )
        SettingsPage.NETWORK -> StreamQualityPage(
            settings,
            focusedRow,
            viewModel,
            StreamQualitySection.NETWORK,
        )
        SettingsPage.SECOND_SCREEN -> StreamControlsPage(
            settings,
            focusedRow,
            viewModel,
            StreamControlsSection.SECOND_SCREEN,
        )
        SettingsPage.POINTER -> StreamControlsPage(
            settings,
            focusedRow,
            viewModel,
            StreamControlsSection.POINTER,
        )
        SettingsPage.COMPUTERS -> StreamHostsPage(settings, focusedRow, viewModel)
        SettingsPage.DISPLAY -> DisplayPage(settings, focusedRow, viewModel)
        SettingsPage.CONTROLLER -> ControllerPage(settings, focusedRow, viewModel)
        SettingsPage.SESSION -> StreamControlsPage(
            settings,
            focusedRow,
            viewModel,
            StreamControlsSection.SESSION,
        )
    }
}

/** Loki's filled category-row treatment, adapted to Moonlight's flat pages. */
@Composable
private fun MoonlightCategoryRow(
    page: SettingsPage,
    selected: Boolean,
    cursorHere: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val shape = ThorTheme.shapes.panel
    val tint = colors.tint(page.slot)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = dimens.spacingSmall,
                vertical = if (compact) 1.dp else 4.dp,
            )
            .clip(shape)
            .background(if (selected) colors.surfaceHighest else Color.Transparent)
            .thorCursor(focused = cursorHere, shape = shape)
            .clickable(onClick = onClick)
            .padding(
                horizontal = dimens.spacingSmall,
                vertical = if (compact) 5.dp else 13.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(if (compact) 22.dp else 38.dp)
                    .clip(ThorTheme.shapes.pill)
                    .background(Brush.verticalGradient(colors.accentStops)),
            )
        }
        Box(
            modifier = Modifier
                .size(if (compact) 26.dp else 44.dp)
                .clip(ThorTheme.shapes.small)
                .background(
                    if (selected) tint.copy(alpha = 0.18f) else colors.surfaceElevated,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = if (selected) tint else tint.copy(alpha = 0.72f),
                modifier = Modifier.size(if (compact) 15.dp else 23.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = page.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = if (selected) {
                colors.onSurface
            } else {
                colors.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.size(if (compact) 14.dp else 18.dp),
        )
    }
}

/** The same accent-bar detail heading used by Loki's settings pages. */
@Composable
private fun SettingsDetailHeader(page: SettingsPage) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface.copy(alpha = 0.48f))
            .padding(
                start = dimens.spacingLarge,
                end = dimens.spacingLarge,
                top = dimens.spacing,
                bottom = dimens.spacingSmall,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = dimens.spacingSmall)
                .width(4.dp)
                .height(42.dp)
                .clip(ThorTheme.shapes.pill)
                .background(Brush.verticalGradient(colors.accentStops)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SETTINGS CATEGORY",
                style = MaterialTheme.typography.labelSmall,
                color = colors.cursor,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
            )
            Text(
                text = page.summary,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val RAIL_WIDTH = 256
private const val CONTENT_MAX_WIDTH = 820
private const val MAX_CATEGORY_ROW = 88
private const val COMFORTABLE_CATEGORY_ROW = 72

/** An index no row has, so nothing is lit while the cursor is on the rail. */
private const val NO_ROW = -1
