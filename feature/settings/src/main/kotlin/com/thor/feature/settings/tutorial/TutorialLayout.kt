package com.thor.feature.settings.tutorial

/** The walkthrough has to serve the Thor's wide upper and compact lower panels. */
internal enum class TutorialLayoutMode { COMPACT, WIDE }

/**
 * Selects layout from the space the card really received after design scaling.
 *
 * Width is the primary distinction between the Thor panels. The height guard
 * also keeps a short external display or a heavily inset window out of the
 * side-by-side layout, where neither column would have enough reading room.
 */
internal fun tutorialLayoutMode(
    widthDp: Float,
    heightDp: Float,
): TutorialLayoutMode = if (
    widthDp >= WIDE_MIN_WIDTH_DP && heightDp >= WIDE_MIN_HEIGHT_DP
) {
    TutorialLayoutMode.WIDE
} else {
    TutorialLayoutMode.COMPACT
}

internal const val WIDE_MIN_WIDTH_DP = 820f
internal const val WIDE_MIN_HEIGHT_DP = 560f
