package com.thor.core.ui.motion

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

/**
 * Scroll motion shared by every controller-driven screen.
 *
 * A focus move still has to reveal its destination when Reduce motion is on; it
 * just gets there without showing the journey. Keeping that rule here prevents
 * new shelves and settings pickers from accidentally reintroducing animation.
 */
suspend fun LazyListState.revealItem(
    index: Int,
    scrollOffset: Int = 0,
    animate: Boolean,
) {
    if (animate) animateScrollToItem(index, scrollOffset) else scrollToItem(index, scrollOffset)
}

suspend fun LazyGridState.revealItem(
    index: Int,
    scrollOffset: Int = 0,
    animate: Boolean,
) {
    if (animate) animateScrollToItem(index, scrollOffset) else scrollToItem(index, scrollOffset)
}

suspend fun LazyListState.revealBy(pixels: Float, animate: Boolean) {
    if (animate) animateScrollBy(pixels) else scrollBy(pixels)
}

suspend fun ScrollState.revealBy(pixels: Float, animate: Boolean) {
    if (animate) animateScrollBy(pixels) else scrollBy(pixels)
}

suspend fun ScrollState.revealTo(value: Int, animate: Boolean) {
    if (animate) animateScrollTo(value) else scrollTo(value)
}
