package com.thor.core.streaming

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The decoded dimensions of one video stream.
 *
 * These are reported by the decoder rather than assumed from the request. A
 * host may negotiate a fallback mode, and pointer coordinates have to follow
 * the pixels that are actually visible or every mismatch becomes a systematic
 * offset on the remote desktop.
 */
data class StreamVideoSize(
    val width: Int,
    val height: Int,
) {
    /** Whether both dimensions describe a drawable video frame. */
    val isValid: Boolean get() = width > 0 && height > 0
}

/** A point expressed in the decoded stream's coordinate space. */
data class StreamPosition(
    val x: Int,
    val y: Int,
    val referenceWidth: Int,
    val referenceHeight: Int,
)

/**
 * Maps a point on an Android surface into a letterboxed video frame.
 *
 * `MediaCodec` uses scale-to-fit for these surfaces. When the view and stream
 * have different aspect ratios, the unused area is black; treating those bars
 * as video stretches pointer input and makes the cursor miss what was touched.
 * This mapper reconstructs the fitted content rectangle and clamps touches in
 * a bar to the nearest video edge.
 */
object StreamViewport {

    /**
     * Converts [viewX]/[viewY] into [videoSize]'s coordinate system.
     *
     * @param viewWidth Width of the touched Android view in pixels.
     * @param viewHeight Height of the touched Android view in pixels.
     * @param videoSize Dimensions the decoder reported for this stream.
     * @return a mapped point, or null when either geometry is not usable.
     */
    fun map(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        videoSize: StreamVideoSize,
    ): StreamPosition? {
        if (viewWidth <= 0 || viewHeight <= 0 || !videoSize.isValid) return null

        val scale = min(
            viewWidth.toFloat() / videoSize.width,
            viewHeight.toFloat() / videoSize.height,
        )
        if (!scale.isFinite() || scale <= 0f) return null

        val contentWidth = videoSize.width * scale
        val contentHeight = videoSize.height * scale
        val contentLeft = (viewWidth - contentWidth) / 2f
        val contentTop = (viewHeight - contentHeight) / 2f

        val x = ((viewX - contentLeft).coerceIn(0f, contentWidth) / contentWidth * videoSize.width)
            .roundToInt()
            .coerceIn(0, videoSize.width)
        val y = ((viewY - contentTop).coerceIn(0f, contentHeight) / contentHeight * videoSize.height)
            .roundToInt()
            .coerceIn(0, videoSize.height)

        return StreamPosition(
            x = x,
            y = y,
            referenceWidth = videoSize.width,
            referenceHeight = videoSize.height,
        )
    }
}
