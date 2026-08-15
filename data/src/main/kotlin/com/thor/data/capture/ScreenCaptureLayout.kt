package com.thor.data.capture

import kotlin.math.roundToInt

/** One physical panel that belongs in a device recording. */
data class CaptureDisplay(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/**
 * The two physical displays and the video frame they are stacked into.
 *
 * [top] is supplied to MediaProjection. Android only permits that API to stream
 * the default display. [bottom], when present, is supplied by Loki's already
 * granted accessibility screenshot service. Keeping those facts in one value is
 * what prevents the output geometry and its two sources drifting apart.
 */
data class ScreenCaptureLayout(
    val outputWidth: Int,
    val outputHeight: Int,
    val outputDensityDpi: Int,
    val top: CaptureDisplay,
    val bottom: CaptureDisplay? = null,
)

/** A GL viewport, whose origin is at the bottom-left of the encoder surface. */
internal data class CaptureViewport(
    val left: Int,
    val bottom: Int,
    val width: Int,
    val height: Int,
)

internal data class CaptureViewports(
    val top: CaptureViewport,
    val bottom: CaptureViewport?,
)

/** Applies the encoder ceiling without changing the frame's proportions. */
internal fun ScreenCaptureLayout.scaledForEncoder(): ScreenCaptureLayout {
    val scale = captureScale(outputWidth, outputHeight)
    if (scale == 1f) return this

    return copy(
        outputWidth = (outputWidth * scale).roundToInt().roundToEven(),
        outputHeight = (outputHeight * scale).roundToInt().roundToEven(),
        outputDensityDpi = (outputDensityDpi * scale).roundToInt().coerceAtLeast(160),
    )
}

/** Places each panel at its real relative size, centred on a black frame. */
internal fun captureViewports(layout: ScreenCaptureLayout): CaptureViewports {
    val outputWidth = layout.outputWidth.coerceAtLeast(2)
    val outputHeight = layout.outputHeight.coerceAtLeast(2)
    val top = layout.top.validated()
    val bottom = layout.bottom?.validated()

    val naturalWidth = maxOf(top.width, bottom?.width ?: 0, 1)
    val naturalHeight = top.height + (bottom?.height ?: 0)
    val scale = minOf(
        outputWidth.toFloat() / naturalWidth,
        outputHeight.toFloat() / naturalHeight.coerceAtLeast(1),
    )

    val topWidth = (top.width * scale).roundToInt().coerceIn(1, outputWidth)
    val topHeight = (top.height * scale).roundToInt().coerceIn(1, outputHeight)
    val bottomWidth = bottom?.let {
        (it.width * scale).roundToInt().coerceIn(1, outputWidth)
    }
    val bottomHeight = bottom?.let {
        (it.height * scale).roundToInt().coerceIn(1, outputHeight)
    }

    val usedHeight = topHeight + (bottomHeight ?: 0)
    val lowerEdge = ((outputHeight - usedHeight) / 2).coerceAtLeast(0)
    val topViewport = CaptureViewport(
        left = (outputWidth - topWidth) / 2,
        bottom = lowerEdge + (bottomHeight ?: 0),
        width = topWidth,
        height = topHeight,
    )
    val bottomViewport = if (bottomWidth != null && bottomHeight != null) {
        CaptureViewport(
            left = (outputWidth - bottomWidth) / 2,
            bottom = lowerEdge,
            width = bottomWidth,
            height = bottomHeight,
        )
    } else {
        null
    }

    return CaptureViewports(topViewport, bottomViewport)
}

private fun CaptureDisplay.validated(): CaptureDisplay = copy(
    width = width.coerceAtLeast(1),
    height = height.coerceAtLeast(1),
    densityDpi = densityDpi.coerceAtLeast(1),
)

private fun Int.roundToEven(): Int = (this / 2 * 2).coerceAtLeast(2)
