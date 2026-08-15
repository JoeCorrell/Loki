package com.thor.launcher.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import com.thor.core.display.DisplayTopology
import com.thor.core.ui.component.stackedFrameSize
import com.thor.data.capture.CaptureDisplay
import com.thor.data.capture.ScreenCaptureLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the physical panels a service-owned screen recording must capture.
 *
 * The launcher reports the exact pair it selected, excluding cast targets and
 * private virtual displays. The foreground service can then keep using those
 * immutable display ids and dimensions after every Loki window has been covered.
 */
@Singleton
class RecordingGeometry @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val displayManager: DisplayManager? =
        context.getSystemService(DisplayManager::class.java)

    @Volatile
    private var launcherLayout: ScreenCaptureLayout? = null

    /** Reported whenever the launcher's physical display topology changes. */
    fun setLauncherPanels(top: CaptureDisplay, bottom: CaptureDisplay?) {
        launcherLayout = buildLayout(top, bottom)
    }

    /**
     * The current panels, stacked at their real relative sizes.
     *
    * A cached topology is accepted only while every display still exists. This
     * pair contributes only its display ids; dimensions are read again so a
     * resolution change while Loki is covered cannot produce a stretched video.
     */
    fun layout(): ScreenCaptureLayout {
        launcherLayout?.let cachedLayout@ { cached ->
            val currentTop = displayManager?.getDisplay(cached.top.displayId)?.toCaptureDisplay()
            val currentBottom = cached.bottom?.let { panel ->
                displayManager?.getDisplay(panel.displayId)?.toCaptureDisplay()
                    ?: return@cachedLayout
            }
            if (currentTop != null) return buildLayout(currentTop, currentBottom)
        }

        val top = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.toCaptureDisplay()
            ?: CaptureDisplay(
                displayId = Display.DEFAULT_DISPLAY,
                width = FALLBACK_WIDTH,
                height = FALLBACK_HEIGHT,
                densityDpi = DisplayMetrics.DENSITY_DEFAULT,
            )
        val bottom = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull(DisplayTopology::isUsableSecondary)
            ?.toCaptureDisplay()

        return buildLayout(top, bottom)
    }

    private fun buildLayout(
        top: CaptureDisplay,
        bottom: CaptureDisplay?,
    ): ScreenCaptureLayout {
        if (bottom == null) {
            return ScreenCaptureLayout(
                outputWidth = top.width,
                outputHeight = top.height,
                outputDensityDpi = top.densityDpi,
                top = top,
            )
        }

        val frame = stackedFrameSize(
            topWidthPx = top.width,
            topAspect = top.aspectRatio,
            bottomWidthPx = bottom.width,
            bottomAspect = bottom.aspectRatio,
        )
        return ScreenCaptureLayout(
            outputWidth = frame.width,
            outputHeight = frame.height,
            outputDensityDpi = top.densityDpi,
            top = top,
            bottom = bottom,
        )
    }

    @Suppress("DEPRECATION")
    private fun Display.toCaptureDisplay(): CaptureDisplay {
        val metrics = DisplayMetrics().also(::getRealMetrics)
        return CaptureDisplay(
            displayId = displayId,
            width = metrics.widthPixels.coerceAtLeast(1),
            height = metrics.heightPixels.coerceAtLeast(1),
            densityDpi = metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT),
        )
    }

    private val CaptureDisplay.aspectRatio: Float
        get() = width.toFloat() / height.coerceAtLeast(1)

    private companion object {
        const val FALLBACK_WIDTH = 1920
        const val FALLBACK_HEIGHT = 1080
    }
}
