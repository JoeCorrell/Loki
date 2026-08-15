package com.thor.feature.stream.session

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.thor.core.datastore.SettingsRepository
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.display.DisplayTopology
import com.thor.core.display.SecondaryDisplay
import com.thor.core.model.AccessibilitySettings
import com.thor.core.model.DisplaySettings
import com.thor.core.model.DualScreenMode
import com.thor.core.model.PerformanceSettings
import com.thor.core.model.PersonalizationSettings
import com.thor.core.model.SessionQuality
import com.thor.core.streaming.StreamPad
import com.thor.feature.stream.panel.StreamPadPanel
import com.thor.feature.stream.panel.StreamPanelController
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Puts the trackpad and keyboard on the second screen for as long as a stream is
 * up.
 *
 * Draws nothing where it is placed — it owns a window on another display, which
 * is why it is composed into a zero-sized view inside the streaming activity
 * rather than into a layout.
 *
 * The display is found here rather than injected, because this window has no
 * launcher state to read: the stream activity is its own task and the topology
 * it needs is one number, which the platform already knows.
 */
@Composable
fun StreamPadHost(
    pad: StreamPad,
    quality: SessionQuality,
    controller: StreamPanelController,
    /** Where the panel's theme comes from; see the theme scope below. */
    settings: SettingsRepository,
    /**
     * Where this window publishes its surface when the panel is a second display.
     *
     * Null keeps the panel as the trackpad, which is what it has always been.
     * Non-null does not by itself mean a second display happens — the host still
     * has to agree — but it is what lets the session ask.
     */
    secondDisplaySurface: SecondDisplaySurface? = null,
) {
    val context = LocalContext.current
    var displayId by remember { mutableStateOf(secondaryDisplayId(context)) }
    var monitorAttached by remember { mutableStateOf(hasMonitor(context)) }

    /*
     * Followed rather than read once.
     *
     * The panel is exposed and withdrawn by the lid and by the ROM's own
     * behaviour while an app is fullscreen, and a display id captured at launch
     * would leave the second screen blank for the rest of the session with no
     * indication why.
     */
    DisposableEffect(context) {
        val manager = context.getSystemService(DisplayManager::class.java)
        fun resample() {
            displayId = secondaryDisplayId(context)
            monitorAttached = hasMonitor(context)
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = resample()

            override fun onDisplayRemoved(id: Int) = resample()

            override fun onDisplayChanged(id: Int) = resample()
        }
        manager?.registerDisplayListener(listener, null)
        onDispose { manager?.unregisterDisplayListener(listener) }
    }

    /*
     * Couch Mode has no second screen to put a trackpad on.
     *
     * Everything else about this window assumes the device is in the user's
     * hands: the pad is reached with a thumb, and it is drawn on the panel
     * directly below the one being watched. Docked to a television none of that
     * holds — the user is across the room from both panels, and the window
     * landed on whichever display Android listed first, which is how a trackpad
     * ended up drawn over the stream on the monitor.
     *
     * Nothing is lost by standing it down. The controller is already forwarded
     * to the PC whole, and a pointer nobody can reach is not a pointer.
     */
    val display = settings.display.collectAsState(initial = DisplaySettings())

    SecondaryDisplay(
        displayId = displayId,
        // This window belongs to the stream activity, not to the launcher. Home
        // or a task switch must therefore remove it with the primary window.
        keepVisibleWhileStopped = false,
        /*
         * Every term read inside the lambda, none of them captured outside it.
         *
         * This is the hazard [SecondaryDisplay.enabled] is a lambda to avoid,
         * and computing `couchMode` above the call walked straight into it. The
         * activity's composition is paused the moment the stream covers the
         * display, so a value worked out during composition is frozen at
         * whatever it was on the first frame — and on the first frame
         * `collectAsState` has only the initial `DisplaySettings()`, because the
         * real ones are still being read off disk. That default is `AUTO` with
         * `couchOnExternalDisplay` on, so the answer was decided before the
         * user's actual mode was known and could never be revised: a device in
         * dual display lost its trackpad and keyboard for the whole session,
         * with no way to get them back short of ending the stream.
         *
         * Read here instead and `snapshotFlow` sees each change directly, on a
         * coroutine that keeps running while the activity is stopped — which is
         * the entire point of the parameter's shape.
         */
        enabled = {
            val settingsNow = display.value
            val couchMode = settingsNow.mode == DualScreenMode.COUCH ||
                (
                    settingsNow.mode == DualScreenMode.AUTO &&
                        settingsNow.couchOnExternalDisplay &&
                        monitorAttached
                    )
            // A second display keeps this window up on its own account, even
            // with the trackpad switched off: the panel is showing the PC rather
            // than a control surface, so `bottomPanel` has nothing to say about
            // whether it should exist.
            val wanted = quality.bottomPanel || quality.secondDisplay
            wanted && !couchMode && displayId != null
        },
        /*
         * Never takes focus, and this is the important line in the file.
         *
         * Focus here costs the streaming window its own — `setFocusable(true)`
         * makes this panel the key target — and the streaming window is what
         * forwards the controller to the PC. A focused trackpad would therefore
         * take the entire pad away from the game: the picture would keep moving
         * and no button would do anything, which reads as the stream having
         * frozen.
         *
         * Nothing is lost by refusing it. Focus governs *key* delivery; touch is
         * delivered to whichever window is under the finger regardless, so the
         * trackpad and the keyboard work exactly as well without it.
         */
        takesFocus = { false },
    ) {
        /*
         * Its own theme scope, carrying the user's theme.
         *
         * This window is outside the launcher's composition and inherits nothing
         * from it, so the theme has to be re-provided on this side of the
         * boundary — exactly as the launcher's own second panel does.
         *
         * It used to be left at defaults, on the reasoning that a trackpad was
         * not worth making the stream wait on a DataStore read. The keyboard is
         * the part that made that wrong: it is the same `ThorKeyboard` the
         * launcher raises everywhere else, and drawn in the default theme while
         * the launcher wore the user's it was visibly a different keyboard —
         * different colours, different surface treatment, different corners.
         *
         * Nothing waits. Each flow is collected with the same default as its
         * starting value, so the first frame is what it always was and the real
         * theme arrives a frame or two later. The activity already reads this
         * store for the keyboard's haptics and sound, for the same reason.
         *
         * Collected here, inside the presentation's own composition, rather than
         * up in the activity's: this window runs its own recomposer precisely so
         * it keeps working when the activity's composition is not, and a value
         * derived up there would freeze while the stream is in front.
         */
        val personalization by settings.personalization
            .collectAsState(initial = PersonalizationSettings())
        val accessibility by settings.accessibility
            .collectAsState(initial = AccessibilitySettings())
        val performance by settings.performance
            .collectAsState(initial = PerformanceSettings())

        ThorTheme(
            personalization = personalization,
            accessibility = accessibility,
            performance = performance,
        ) {
            /*
             * The panel is either the PC's second screen or the trackpad, never
             * both — it is one surface, and a desktop drawn under a trackpad
             * would be a picture nobody can see behind controls that cannot be
             * reached.
             *
             * The choice is made from the setting rather than from whether the
             * host agreed, and that is deliberate. The surface has to exist
             * *before* the session starts in order to be offered at all, so it
             * cannot wait on an answer that only arrives afterwards. A host that
             * declines leaves this drawing an empty surface, and
             * `StreamSessionActivity` swaps it back to the trackpad once it
             * knows — one frame of black rather than a trackpad that flickers
             * away and returns.
             */
            val secondDisplayActive by (secondDisplaySurface?.active
                ?: MutableStateFlow(false)).collectAsState()

            if (secondDisplaySurface != null && quality.secondDisplay && secondDisplayActive) {
                SecondDisplayPanel(
                    surface = secondDisplaySurface,
                    quality = quality,
                    controller = controller,
                )
            } else {
                StreamPadPanel(pad = pad, quality = quality, controller = controller)
            }
        }
    }
}

/**
 * The first display that is not the built-in one.
 *
 * `DISPLAY_CATEGORY_PRESENTATION` is the right question to ask: it lists exactly
 * the displays a `Presentation` may be shown on, which is not the same as every
 * display the device reports.
 */
private fun secondaryDisplayId(context: Context): Int? {
    val manager = context.getSystemService(DisplayManager::class.java) ?: return null
    return manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        ?.displayId
}

/**
 * The second panel's size in real pixels, for the mode a second display asks for.
 *
 * Real metrics rather than the ones the window would report: this is what the
 * host should encode, and a size reduced by insets or by the app's own window
 * would have the PC render a desktop smaller than the screen it lands on.
 *
 * Falls back to the default display when there is no second panel. Nothing uses
 * the answer in that case — with no panel there is no surface, so no second
 * display is ever requested — but returning zeroes would put a zero into a
 * session config, and a plausible number is safer than one that cannot be valid.
 */
internal fun secondaryDisplayMetrics(context: Context): Pair<Int, Int> {
    val manager = context.getSystemService(DisplayManager::class.java)
    val display = manager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        ?: manager?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return DEFAULT_PANEL_WIDTH to DEFAULT_PANEL_HEIGHT

    @Suppress("DEPRECATION")
    val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
    return if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
        metrics.widthPixels to metrics.heightPixels
    } else {
        DEFAULT_PANEL_WIDTH to DEFAULT_PANEL_HEIGHT
    }
}

private const val DEFAULT_PANEL_WIDTH = 1920
private const val DEFAULT_PANEL_HEIGHT = 1080

/**
 * Whether a screen is attached beyond the two the device was built with.
 *
 * Counted rather than named, exactly as [DisplayTopology.hasExternalDisplay]
 * counts it: the public API cannot be asked whether a display is built in, and a
 * monitor's name is whatever its EDID happens to say. The Thor has two panels of
 * its own, so a second presentation display is one the user plugged in.
 *
 * Asked here rather than read from the launcher, because this window is a
 * separate task with none of the launcher's state — and the same reasoning that
 * put the display lookup in this file puts this beside it.
 */
private fun hasMonitor(context: Context): Boolean {
    val manager = context.getSystemService(DisplayManager::class.java) ?: return false
    return manager.displays
        .distinctBy { it.displayId }
        .count(DisplayTopology::isUsableSecondary) >= EXTERNAL_THRESHOLD
}

/** The device's own second panel, and then another one. */
private const val EXTERNAL_THRESHOLD = 2
