package com.moonlight.ds

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.thor.core.datastore.SettingsRepository
import com.thor.core.display.ThorDisplayMonitor
import com.thor.core.display.hideSystemBars
import com.thor.core.input.ControllerInputRouter
import com.thor.core.input.ControllerProfiles
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Moonlight DS's single activity.
 *
 * Deliberately the same shape as Loki's `LauncherActivity`, because the thing it
 * hosts is the same: it owns the physical input stream, the window flags, and
 * the lifecycle the secondary-display presentation attaches to. Everything else
 * is delegated to [MoonlightApp].
 *
 * What is absent is what a launcher needs and an application does not — the home
 * intent, the foreground reporting, the pointer service and the recording
 * geometry. The router is therefore built without a [ControllerInputRouter]
 * pointer hook, which only exists to feed the accessibility cursor with stick
 * motion; there is no accessibility service here to feed.
 */
@AndroidEntryPoint
class MoonlightActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var displayMonitor: ThorDisplayMonitor

    /**
     * Input is routed through a single router scoped to this activity, so key
     * repeat timers and long-press watches die with the window rather than
     * outliving it.
     */
    private val inputRouter: ControllerInputRouter by lazy {
        ControllerInputRouter(lifecycleScope)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /*
         * This activity belongs on the main panel and nowhere else.
         *
         * The second panel is a `Presentation` owned by this activity, so an
         * instance of *this* over there would be two of the app at once: two
         * compositions, two view models, and two presentations each trying to
         * claim the other's display.
         *
         * It is reachable by mistake, because a started activity inherits the
         * starting one's display and a task cannot span two — leaving the stream
         * window from a context on the second panel is enough to do it.
         */
        @Suppress("DEPRECATION")
        val activityDisplayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.displayId
        } else {
            windowManager.defaultDisplay.displayId
        }
        val onWrongPanel = activityDisplayId?.let { it != Display.DEFAULT_DISPLAY } == true
        if (onWrongPanel) {
            finish()
            return
        }

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars(window)

        // Controller remapping takes effect immediately, without a restart —
        // the same profiles, resolved the same way, as in Loki.
        settingsRepository.controls
            .onEach { controls ->
                val profile = ControllerProfiles.byId(
                    controls.activeProfileId,
                    controls.customProfiles,
                )
                // Sensitivity is expressed as a multiplier but applied as an
                // inverse dead zone: a more sensitive stick registers a
                // direction sooner, i.e. at a smaller deflection.
                val deadZone = (profile.stickDeadZone / controls.stickSensitivity)
                    .coerceIn(MIN_DEAD_ZONE, MAX_DEAD_ZONE)
                inputRouter.setProfile(profile.copy(stickDeadZone = deadZone))
            }
            .launchIn(lifecycleScope)

        setContent {
            MoonlightApp(
                inputRouter = inputRouter,
                displayMonitor = displayMonitor,
            )
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)

        if (isTopResumedActivity) {
            // The system restores the bars whenever something else has been in
            // front — the stream window, most often — so full screen is
            // re-asserted on the way back rather than only in onCreate.
            hideSystemBars(window)
        } else {
            // A direction held at the moment focus moved away would otherwise
            // leave the auto-repeat timer running against a window that can no
            // longer see the key-up.
            inputRouter.releaseAll()
        }
    }

    /**
     * Intercepts physical input before Compose sees it.
     *
     * `dispatchKeyEvent` rather than `onKeyDown` because this interface has its
     * own cursor model, exactly as Loki's does, and letting the framework's focus
     * traversal also run would fight it.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        // The same entry point the secondary panel uses, so whichever window
        // holds focus drives one cursor.
        inputRouter.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onGenericMotionEvent(event: MotionEvent): Boolean =
        inputRouter.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Losing focus while a direction is held would otherwise leave the
        // auto-repeat timer running against a window that can no longer see the
        // key-up event.
        if (!hasFocus) inputRouter.releaseAll() else hideSystemBars(window)
    }

    override fun onPause() {
        super.onPause()
        inputRouter.releaseAll()
    }

    private companion object {
        /** Bounds on the derived dead zone, so no setting makes the stick unusable. */
        const val MIN_DEAD_ZONE = 0.15f
        const val MAX_DEAD_ZONE = 0.85f
    }
}
