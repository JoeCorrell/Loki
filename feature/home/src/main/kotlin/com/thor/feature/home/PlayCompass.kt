package com.thor.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thor.core.designsystem.component.GlassSurface
import com.thor.core.designsystem.modifier.SurfaceLevel
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.model.GameEntry
import com.thor.core.model.completionProgress
import com.thor.core.model.completionSeconds
import com.thor.core.ui.component.ArtworkImage
import kotlin.math.abs

/**
 * The question a player is answering, not a genre or an opaque recommendation
 * category. Up and Down move between these intents in the Play Compass overlay.
 */
enum class PlayCompassMode(val label: String, val description: String) {
    CONTINUE("Continue", "Resume something with momentum"),
    QUICK("Quick pick", "Prefer shorter, easy-to-enter games"),
    REDISCOVER("Rediscover", "Bring back something you have not touched lately"),
    SURPRISE("Surprise me", "Rotate through the overlooked part of your library"),
}

/** A recommendation whose explanation is as important as its score. */
data class PlayCompassPick(
    val game: GameEntry,
    val reason: String,
    val detail: String,
    internal val score: Double,
)

/**
 * Local, explainable library discovery. No account, network profile, or hidden
 * model: the same play history and metadata Loki already stores produce the deck.
 */
object PlayCompass {
    fun recommend(
        entries: Collection<GameEntry>,
        mode: PlayCompassMode,
        nowEpochMs: Long = System.currentTimeMillis(),
        limit: Int = 8,
    ): List<PlayCompassPick> {
        if (limit <= 0) return emptyList()
        val games = entries.filterNot { it.isHidden || it.isMissing }
        if (games.isEmpty()) return emptyList()

        val ranked = games.mapNotNull { game ->
            when (mode) {
                PlayCompassMode.CONTINUE -> continuePick(game, nowEpochMs)
                PlayCompassMode.QUICK -> quickPick(game)
                PlayCompassMode.REDISCOVER -> rediscoverPick(game, nowEpochMs)
                PlayCompassMode.SURPRISE -> surprisePick(game, nowEpochMs)
            }
        }.sortedWith(compareByDescending<PlayCompassPick> { it.score }.thenBy { it.game.sortTitle })

        // An empty Continue/Rediscover shelf on a fresh install is a dead feature.
        // Fall back honestly to new games and say why, without pretending history exists.
        val usable = if (ranked.isNotEmpty()) ranked else games.map { game ->
            PlayCompassPick(
                game = game,
                reason = "Start something new",
                detail = game.metadata.genres.firstOrNull() ?: "Not played yet",
                score = if (game.isFavorite) 10.0 else 0.0,
            )
        }.sortedByDescending(PlayCompassPick::score)

        return usable.take(limit)
    }

    private fun continuePick(game: GameEntry, now: Long): PlayCompassPick? {
        if (!game.stats.hasBeenPlayed) return null
        val progress = game.completionProgress()
        val ageDays = ageDays(game.stats.lastPlayedEpochMs, now)
        val recency = (45.0 - ageDays.coerceAtMost(45)).coerceAtLeast(0.0)
        val momentum = progress?.let { 35.0 - abs(it - 0.55f) * 35.0 } ?: 12.0
        val score = recency + momentum + if (game.isFavorite) 12.0 else 0.0
        return PlayCompassPick(
            game = game,
            reason = progress?.let { "Continue at ${(it * 100).toInt()}%" } ?: "Continue your run",
            detail = remainingLabel(game, progress) ?: lastPlayedLabel(ageDays),
            score = score,
        )
    }

    private fun quickPick(game: GameEntry): PlayCompassPick {
        val seconds = game.metadata.completionSeconds
        val hours = seconds?.div(3600f)
        val lengthScore = when {
            hours == null -> 8.0
            hours <= 4f -> 55.0
            hours <= 10f -> 42.0
            hours <= 20f -> 24.0
            else -> 4.0
        }
        val score = lengthScore + if (game.isFavorite) 12.0 else 0.0 - game.stats.launchCount.coerceAtMost(8)
        return PlayCompassPick(
            game = game,
            reason = when {
                hours == null -> "Easy library pick"
                hours < 1f -> "Under an hour"
                else -> "About ${hours.toInt().coerceAtLeast(1)}h total"
            },
            detail = game.metadata.genres.take(2).joinToString(" · ").ifBlank { "Ready to play" },
            score = score,
        )
    }

    private fun rediscoverPick(game: GameEntry, now: Long): PlayCompassPick? {
        if (!game.stats.hasBeenPlayed) return null
        val ageDays = ageDays(game.stats.lastPlayedEpochMs, now)
        if (ageDays < 14) return null
        return PlayCompassPick(
            game = game,
            reason = if (ageDays >= 365) "Not played in over a year" else "Not played for $ageDays days",
            detail = "${game.stats.launchCount} launches · ${playedLabel(game)}",
            score = ageDays.coerceAtMost(730).toDouble() + if (game.isFavorite) 45.0 else 0.0,
        )
    }

    private fun surprisePick(game: GameEntry, now: Long): PlayCompassPick {
        val day = now / DAY_MS
        val rotation = dailyHash(game.id, day).toDouble()
        val overlooked = if (!game.stats.hasBeenPlayed) 2_000_000_000.0 else 0.0
        val rating = (game.metadata.rating ?: 0) * 100_000.0
        return PlayCompassPick(
            game = game,
            reason = if (game.stats.hasBeenPlayed) "A different choice for today" else "Unplayed in your library",
            detail = listOfNotNull(
                game.metadata.genres.firstOrNull(),
                game.metadata.releaseYear?.toString(),
            ).joinToString(" · ").ifBlank { "Daily rotating pick" },
            score = overlooked + rating + rotation,
        )
    }

    private fun remainingLabel(game: GameEntry, progress: Float?): String? {
        val seconds = game.metadata.completionSeconds ?: return null
        val remaining = (seconds * (1f - (progress ?: 0f))).toLong().coerceAtLeast(0L)
        return "Roughly ${durationLabel(remaining)} remaining"
    }

    private fun playedLabel(game: GameEntry): String =
        "${durationLabel(game.stats.totalPlayMillis / 1000)} played"

    private fun lastPlayedLabel(ageDays: Long): String = when (ageDays) {
        0L -> "Played today"
        1L -> "Played yesterday"
        else -> "Played $ageDays days ago"
    }

    private fun ageDays(then: Long?, now: Long): Long =
        then?.let { ((now - it).coerceAtLeast(0L) / DAY_MS) } ?: Long.MAX_VALUE / 2

    private fun durationLabel(seconds: Long): String = when {
        seconds < 3_600 -> "${(seconds / 60).coerceAtLeast(1)}m"
        seconds < 36_000 -> "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
        else -> "${seconds / 3_600}h"
    }

    /** Small avalanche hash so changing the day rotates order, not just its low bits. */
    private fun dailyHash(id: String, day: Long): Long {
        var value = id.hashCode().toLong() xor day
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return (value xor (value ushr 33)) and 0x7FFFFFFF
    }

    private const val DAY_MS = 86_400_000L
}

@Composable
fun PlayCompassScreen(
    picks: List<PlayCompassPick>,
    mode: PlayCompassMode,
    focusedIndex: Int,
    onModeSelected: (PlayCompassMode) -> Unit,
    onPickSelected: (Int) -> Unit,
    onLaunch: (GameEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val selectedIndex = focusedIndex.coerceIn(0, (picks.size - 1).coerceAtLeast(0))
    val pick = picks.getOrNull(selectedIndex)

    GlassSurface(
        shape = ThorTheme.shapes.panel,
        color = colors.surface,
        level = SurfaceLevel.RAISED,
        modifier = modifier.fillMaxSize().padding(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(ThorTheme.shapes.small)
                        .background(Brush.linearGradient(listOf(colors.primary, colors.contentAccent))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = colors.onControl, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("PLAY COMPASS", color = colors.onSurface, fontWeight = FontWeight.Black)
                    Text(
                        "Local, explainable picks from your own library",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "↑↓ INTENT  ·  ←→ PICK  ·  A PLAY  ·  B CLOSE",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PlayCompassMode.entries.forEach { candidate ->
                    val active = candidate == mode
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(ThorTheme.shapes.pill)
                            .background(if (active) colors.selection.copy(alpha = 0.2f) else colors.surfaceElevated)
                            .border(1.dp, if (active) colors.selection else colors.outline.copy(alpha = 0.28f), ThorTheme.shapes.pill)
                            .clickable { onModeSelected(candidate) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(candidate.icon, null, tint = if (active) colors.selection else colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text("  ${candidate.label}", color = if (active) colors.onSurface else colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (pick == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = colors.contentAccent, modifier = Modifier.size(48.dp))
                        Text("No playable games yet", color = colors.onSurface, fontWeight = FontWeight.Bold)
                        Text("Scan a ROM folder, then come back for a deck.", color = colors.onSurfaceVariant)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ArtworkImage(
                        model = pick.game.metadata.artwork.hero ?: pick.game.metadata.artwork.boxArt,
                        contentDescription = pick.game.title,
                        fallbackText = pick.game.title,
                        fallbackTint = colors.contentAccent,
                        modifier = Modifier
                            .width(270.dp)
                            .fillMaxHeight()
                            .clip(ThorTheme.shapes.panel),
                    )
                    Column(
                        modifier = Modifier.fillMaxHeight().weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(mode.description.uppercase(), color = colors.contentAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(
                            pick.game.title,
                            color = colors.onSurface,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(pick.reason, color = colors.selection, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(pick.detail, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        pick.game.metadata.description?.let { description ->
                            Text(
                                description,
                                color = colors.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .clip(ThorTheme.shapes.pill)
                                .background(colors.control)
                                .clickable(role = Role.Button) { onLaunch(pick.game) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = colors.onControl)
                            Text("PLAY NOW", color = colors.onControl, fontWeight = FontWeight.Black)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    picks.forEachIndexed { index, candidate ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(width = if (index == selectedIndex) 28.dp else 9.dp, height = 7.dp)
                                .clip(ThorTheme.shapes.pill)
                                .background(if (index == selectedIndex) colors.selection else colors.outline)
                                .clickable { onPickSelected(index) },
                        )
                    }
                    Text("  ${selectedIndex + 1} / ${picks.size}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Nothing is uploaded · Every reason is visible",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "   CLOSE",
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(6.dp),
                    )
                }
            }
        }
    }
}

private val PlayCompassMode.icon: ImageVector
    get() = when (this) {
        PlayCompassMode.CONTINUE -> Icons.Rounded.PlayArrow
        PlayCompassMode.QUICK -> Icons.Rounded.Bolt
        PlayCompassMode.REDISCOVER -> Icons.Rounded.History
        PlayCompassMode.SURPRISE -> Icons.Rounded.Shuffle
    }
