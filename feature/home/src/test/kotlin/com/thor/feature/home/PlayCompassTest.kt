package com.thor.feature.home

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.GameEntry
import com.thor.core.model.GameMetadata
import com.thor.core.model.PlayStats
import org.junit.Test

class PlayCompassTest {

    @Test
    fun `continue is explainable and prefers current momentum`() {
        val current = game("current", daysAgo = 2, launches = 4, playedHours = 5, lengthHours = 10)
        val stale = game("stale", daysAgo = 90, launches = 4, playedHours = 5, lengthHours = 10)

        val picks = PlayCompass.recommend(listOf(stale, current), PlayCompassMode.CONTINUE, NOW)

        assertThat(picks.first().game.id).isEqualTo("current")
        assertThat(picks.first().reason).contains("50%")
        assertThat(picks.first().detail).contains("remaining")
    }

    @Test
    fun `rediscover excludes recent play and ranks the oldest first`() {
        val recent = game("recent", daysAgo = 3, launches = 2)
        val old = game("old", daysAgo = 150, launches = 2)
        val older = game("older", daysAgo = 400, launches = 2)

        val picks = PlayCompass.recommend(
            listOf(recent, old, older),
            PlayCompassMode.REDISCOVER,
            NOW,
        )

        assertThat(picks.map { it.game.id }).containsExactly("older", "old").inOrder()
    }

    @Test
    fun `quick pick favours genuinely shorter games`() {
        val short = game("short", lengthHours = 3)
        val long = game("long", lengthHours = 80, favorite = true)

        val picks = PlayCompass.recommend(listOf(long, short), PlayCompassMode.QUICK, NOW)

        assertThat(picks.first().game.id).isEqualTo("short")
        assertThat(picks.first().reason).contains("3h")
    }

    @Test
    fun `hidden and missing games never enter the deck`() {
        val visible = game("visible")
        val hidden = game("hidden", hidden = true)
        val missing = game("missing", missing = true)

        val picks = PlayCompass.recommend(
            listOf(hidden, missing, visible),
            PlayCompassMode.SURPRISE,
            NOW,
        )

        assertThat(picks.map { it.game.id }).containsExactly("visible")
    }

    @Test
    fun `daily surprise is deterministic and rotates without uploading a profile`() {
        val games = (1..12).map { game("game-$it") }

        val today = PlayCompass.recommend(games, PlayCompassMode.SURPRISE, NOW)
        val again = PlayCompass.recommend(games, PlayCompassMode.SURPRISE, NOW)
        val tomorrow = PlayCompass.recommend(games, PlayCompassMode.SURPRISE, NOW + DAY)

        assertThat(again).isEqualTo(today)
        assertThat(tomorrow.map { it.game.id }).isNotEqualTo(today.map { it.game.id })
    }

    @Test
    fun `a fresh library still gets honest fallback picks`() {
        val picks = PlayCompass.recommend(
            listOf(game("new-one"), game("new-two")),
            PlayCompassMode.CONTINUE,
            NOW,
        )

        assertThat(picks).hasSize(2)
        assertThat(picks.all { it.reason == "Start something new" }).isTrue()
    }

    private fun game(
        id: String,
        daysAgo: Int? = null,
        launches: Int = 0,
        playedHours: Int = 0,
        lengthHours: Int? = null,
        favorite: Boolean = false,
        hidden: Boolean = false,
        missing: Boolean = false,
    ) = GameEntry(
        id = id,
        title = id,
        sortTitle = id,
        platformId = "test",
        contentUri = "file:///$id.rom",
        fileName = "$id.rom",
        fileSizeBytes = 1,
        metadata = GameMetadata(completionMinutes = lengthHours?.times(60)),
        stats = PlayStats(
            totalPlayMillis = playedHours * 3_600_000L,
            launchCount = launches,
            lastPlayedEpochMs = daysAgo?.let { NOW - it * DAY },
        ),
        isFavorite = favorite,
        isHidden = hidden,
        isMissing = missing,
    )

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_800_000_000_000L
    }
}
