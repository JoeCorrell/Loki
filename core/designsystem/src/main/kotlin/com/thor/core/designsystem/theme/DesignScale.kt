package com.thor.core.designsystem.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.min

/**
 * How much larger than the design canvas this surface is being drawn at.
 *
 * Rarely needed — everything sized in dp is already scaled by the density
 * [DesignScale] provides — but exposed for the handful of places that reason in
 * pixels or that want to know whether they have been given a television.
 */
val LocalDesignScale = staticCompositionLocalOf { 1f }

/**
 * Draws its content against a fixed design canvas, whatever the screen is.
 *
 * The problem this solves is that `dp` is density-independent but not
 * *size*-independent. Android's Smallest Width setting changes how many dp a
 * screen is worth, so a panel written as `340.dp` is a third of a 1000dp-wide
 * screen and a half of a 680dp one — while anything written as a fraction of the
 * parent keeps its proportion regardless. Mix the two, which every real layout
 * does, and changing one developer setting pulls the interface apart: the panels
 * hold their share of the screen and the tiles between them shrink.
 *
 * So the composition is given a density chosen to make the window a constant
 * number of design units across. Every dp underneath — spacing, type, corner
 * radii, icon sizes, the fixed constants scattered through the layouts — is then
 * the same fraction of the screen on every device, and the fractional sizes it
 * sits beside no longer disagree with it. Nothing at the call sites changes,
 * which is the point: correcting this by multiplying several hundred constants
 * would leave the next constant somebody writes wrong again.
 *
 * Scaled off the shortest side rather than fitted to both. A fit would punish an
 * aspect ratio the reference did not anticipate — this launcher draws on a wide
 * top panel, a squarer bottom one and a television — and what the calibration is
 * really about is how large a dp should be relative to the screen, which the
 * short side answers on all three.
 *
 * @param referenceShortSide the design canvas's short edge. See
 *   [PANEL_SHORT_SIDE] and [COUCH_SHORT_SIDE] for the two this launcher uses and
 *   why they are the numbers they are.
 * @param userScale a preference on top, applied after the correction so it stays
 *   a plain percentage of a sensible size rather than a number the user has to
 *   compensate with.
 */
@Composable
fun DesignScale(
    referenceShortSide: Int,
    userScale: Float = 1f,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val shortSide = min(maxWidth.value, maxHeight.value)

        val scale = ((shortSide / referenceShortSide) * userScale)
            .coerceIn(MIN_SCALE, MAX_SCALE)

        val scaled = remember(base.density, base.fontScale, scale) {
            Density(density = base.density * scale, fontScale = base.fontScale)
        }

        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalDesignScale provides scale,
            content = content,
        )
    }
}

/**
 * The launcher's own panels: the grid, the information panel, every overlay.
 *
 * A bigger number means a smaller interface. This is the short edge of the
 * design canvas the window is made to be, so raising it says "the screen is
 * worth more design units", each dp becomes a smaller share of the panel, and
 * more fits on it.
 *
 * 540 was the short edge of a 1080p screen at the density Android gives one by
 * default — the canvas this interface was originally drawn against, so on that
 * screen the correction was exactly 1. It read as zoomed in on the hardware
 * this fork is for: the AYN Thor's panels are physically small and sit close to
 * the face, and an interface calibrated to a phone held at arm's length puts
 * about two thirds as much on them as there is room for.
 *
 * 675 was 540 at 80%, chosen as a round fifth rather than tuned, and 80% turned
 * out to be a fifth too far: at that size the rows were legible but tight, and
 * the interface had the density of a desktop rather than of something held.
 * 614 is the same 675 asked to be 10% larger, which lands at 540 × 88%.
 *
 * It is a calibration and not a constraint — a screen half again as tall in dp
 * still gets a density half again as large, and the launcher looks identical on
 * it.
 */
const val PANEL_SHORT_SIDE = 614

/**
 * Couch Mode, which is drawn for a room rather than for a hand.
 *
 * Kept at three quarters the size of a panel's dp, which is the whole point of
 * having a second number: a television should hold about as much as a set-top
 * box does rather than show a handheld's interface enlarged to fill a wall.
 * That ratio is why this moves with [PANEL_SHORT_SIDE] rather than holding a
 * number of its own — leaving it behind would make couch mode's dp *larger*
 * than a panel's and invert the relationship it exists to express.
 */
const val COUCH_SHORT_SIDE = PANEL_SHORT_SIDE * 4 / 3

/**
 * Bounds on the correction.
 *
 * Not expected to bind. They exist because the Smallest Width setting can be put
 * anywhere at all, including values no device ships with, and a launcher that
 * renders at a twentieth scale cannot be used to put it back.
 */
private const val MIN_SCALE = 0.4f
private const val MAX_SCALE = 2.5f
