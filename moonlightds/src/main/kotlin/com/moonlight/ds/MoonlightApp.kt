package com.moonlight.ds

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.moonlight.ds.keyboard.MoonlightKeyboard
import com.moonlight.ds.settings.MoonlightSettingsScreen
import com.moonlight.ds.settings.SettingsController
import com.moonlight.ds.settings.SettingsViewModel
import com.thor.core.common.clipboard.ThorClipboard
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.display.DisplayTopology
import com.thor.core.display.LauncherFocus
import com.thor.core.display.LauncherPanel
import com.thor.core.display.SecondaryDisplay
import com.thor.core.display.ThorDisplayMonitor
import com.thor.core.input.ControllerInputRouter
import com.thor.core.model.ControllerCommand
import com.thor.core.model.DualScreenMode
import com.thor.core.model.KeyboardKey
import com.thor.core.ui.component.ThorKeyboard
import com.thor.core.ui.feedback.FeedbackCue
import com.thor.core.ui.feedback.rememberThorFeedback
import com.thor.core.ui.input.LocalThorTextInput
import com.thor.core.ui.input.ThorTextInputState
import com.thor.feature.stream.StreamEffect
import com.thor.feature.stream.StreamViewModel
import com.thor.feature.stream.couch.StreamCouchScreen
import com.thor.feature.stream.panel.StreamBottomPanel
import com.thor.feature.stream.panel.StreamTopPanel
import com.thor.feature.stream.session.StreamSessionActivity

/**
 * Which of the two surfaces the controller is driving.
 *
 * The same idea as Loki's `InputSurface`, and named for the same reason: a
 * *panel* is a role rather than a screen, because the display settings can swap
 * which window each one is drawn in.
 */
private enum class Surface { TOP, BOTTOM }

/**
 * Moonlight DS's interface.
 *
 * The whole of it is composed from `:feature:stream` — [StreamTopPanel],
 * [StreamBottomPanel] and [StreamCouchScreen] are the same composables Loki
 * draws, taking the same [StreamViewModel]. This function is the part Loki keeps
 * in its own 3,800-line shell: which panel goes in which window, who holds the
 * controller, and what a button press means before it reaches the section.
 *
 * That is deliberately the only thing duplicated. The interface is shared code;
 * the shell around it cannot be, because Loki's also has a grid, an app drawer,
 * six other sections and a home button to answer.
 */
@Composable
internal fun MoonlightApp(
    inputRouter: ControllerInputRouter,
    displayMonitor: ThorDisplayMonitor,
) {
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val streamViewModel: StreamViewModel = hiltViewModel()

    val settings by settingsViewModel.settings.collectAsState()

    ThorTheme(
        personalization = settings.personalization,
        accessibility = settings.accessibility,
        performance = settings.performance,
    ) {
        val streamState by streamViewModel.uiState.collectAsState()
        val clientName by streamViewModel.clientName.collectAsState()

        val feedback = rememberThorFeedback(
            controls = settings.controls,
            audio = settings.audio,
        )

        /*
         * The launcher's text focus, shared by both windows.
         *
         * Reaches the presentation because that window is composed with the
         * activity's `CompositionContext`, so composition locals cross the display
         * boundary. It is what lets the address field sit on one panel while the
         * keyboard filling it in is on the other.
         */
        val textInput = remember { ThorTextInputState() }
        val clipboard = remember(context) { ThorClipboard(context) }
        val keyboard = remember(clipboard) { MoonlightKeyboard(clipboard) }
        val keyboardState by keyboard.state.collectAsState()

        val settingsController = remember { SettingsController() }
        var settingsOpen by remember { mutableStateOf(false) }

        /*
         * The surface the user last touched, which is the lock: it holds until the
         * other surface is touched, so the controller drives the screen the user
         * last put a thumb on.
         */
        var touchedSurface by remember { mutableStateOf(Surface.BOTTOM) }

        /*
         * Set when the stream window takes over, cleared by any touch here.
         *
         * The presentation gives up its claim on window focus so the session can
         * take it. Without this the second window competes for focus with the
         * stream it just started, and the pad drives neither.
         */
        var focusYieldedToApp by remember { mutableStateOf(false) }

        // Reset with the settings screen, so reopening it starts at the top rather
        // than wherever it was left three sessions ago.
        LaunchedEffect(settingsOpen) {
            if (!settingsOpen) settingsController.reset()
        }

        val displays by displayMonitor.displays.collectAsState(
            initial = remember { displayMonitor.snapshot() },
        )

        val topology = remember(displays, settings.display.mode) {
            val primary = displays.firstOrNull { it.isPrimary } ?: displays.firstOrNull()
            primary?.let {
                DisplayTopology(
                    primary = it,
                    secondary = displays.firstOrNull { display ->
                        !display.isPrimary && display.isPresentationCapable
                    },
                    requestedMode = settings.display.mode,
                    hasExternalDisplay = DisplayTopology.hasExternalDisplay(displays),
                )
            }
        }

        val mode = topology?.effectiveMode ?: DualScreenMode.SPLIT_SINGLE
        val secondaryDisplayId = topology?.secondary?.displayId
        val couch = mode == DualScreenMode.COUCH

        /*
         * `swapScreens` says which physical panel holds which surface, and this app
         * honours it for the same reason Loki does: which way up the device is held
         * is the user's business, and the setting is shared between them.
         */
        val bottomInActivityWindow = !settings.display.swapScreens

        /*
         * The PCs are asked how they are for as long as anyone is looking at them.
         *
         * A status is the answer to a question asked just now, so it is asked again
         * whenever this app comes to the front rather than only when a host is
         * first seen.
         */
        LaunchedEffect(Unit) { streamViewModel.refreshAll() }

        /*
         * Showing the stream.
         *
         * Started from here rather than from the section, because starting an
         * activity needs a context and the section is a composable that draws into
         * two windows. The session itself is already held in the process — this
         * intent carries nothing but the instruction to show it.
         */
        LaunchedEffect(streamViewModel) {
            streamViewModel.effects.collect { effect ->
                when (effect) {
                    StreamEffect.OpenSession -> {
                        // The session takes the window; the presentation must stop
                        // asking for focus until the user comes back and touches
                        // something.
                        focusYieldedToApp = true
                        context.startActivity(Intent(context, StreamSessionActivity::class.java))
                    }
                }
            }
        }

        /*
         * The keyboard follows whichever field has claimed text focus.
         *
         * The keyboard is an input method rather than a search box: it edits a
         * buffer, and whatever holds focus receives it. A field is claimed by
         * tapping it, or by the couch add-a-PC form asking on the user's behalf.
         */
        LaunchedEffect(textInput.focusedId) {
            val id = textInput.focusedId
            if (id == null) {
                keyboard.close()
            } else {
                keyboard.open(label = textInput.label, initial = textInput.initialText)
            }
        }

        // Every keystroke goes straight to the field being filled in, so it fills
        // in live on whichever panel it is drawn on while the keyboard stays here.
        LaunchedEffect(keyboardState.text, keyboardState.visible) {
            if (keyboardState.visible && textInput.focusedId != null) {
                textInput.setText(keyboardState.text)
            }
        }

        // Closing the keyboard releases the field with it — a caret left blinking
        // on a field nothing is typing into is a lie.
        LaunchedEffect(keyboardState.visible) {
            if (!keyboardState.visible) textInput.release()
        }

        /*
         * A touch anywhere on a surface claims the controller for it.
         *
         * Registered in the initial pass, so it sees the event before any button on
         * the panel consumes it — the claim is made by touching the panel, not by
         * hitting something on it. It also cancels the focus a stream took away,
         * because reaching for a panel is unambiguously a request to drive it.
         */
        fun Modifier.claimsInputFor(surface: Surface): Modifier =
            this.pointerInput(surface) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        // Written only when the answer changes: a finger resting on
                        // the panel produces a stream of moves, and assigning
                        // snapshot state on each one would recompose the whole shell
                        // dozens of times a second to conclude nothing had moved.
                        if (touchedSurface != surface) touchedSurface = surface
                        if (focusYieldedToApp) focusYieldedToApp = false
                    }
                }
            }

        /*
         * Input, in the order the surfaces outrank one another.
         *
         * The keyboard first, because while it is up every direction belongs to it —
         * a press that fell through would move the PC list underneath the keys. Then
         * the settings screen. Then the section itself, which is offered the command
         * and declines what it does not use, so the shell's own bindings keep
         * working everywhere.
         */
        LaunchedEffect(inputRouter, couch) {
            inputRouter.events.collect { event ->
                val command = event.command

                if (keyboard.handleCommand(command)) {
                    feedback.play(command.toCue())
                    return@collect
                }

                if (settingsOpen) {
                    if (settingsController.handleCommand(command)) {
                        feedback.play(command.toCue())
                    } else if (command == ControllerCommand.BACK) {
                        settingsOpen = false
                        feedback.play(FeedbackCue.BACK)
                    }
                    return@collect
                }

                // Start opens the settings, which is the only shell-level binding
                // this app has. Loki puts its own settings behind the same button.
                if (command == ControllerCommand.OPEN_SIDE_MENU) {
                    settingsOpen = true
                    feedback.play(FeedbackCue.MENU_OPEN)
                    return@collect
                }

                if (streamViewModel.handleCommand(command, couch)) {
                    feedback.play(command.toCue())
                }
            }
        }

        CompositionLocalProvider(LocalThorTextInput provides textInput) {

            /** The PC list: what is on the network and what each machine is doing. */
            val topContent: @Composable () -> Unit = {
                Box(modifier = Modifier.fillMaxSize().claimsInputFor(Surface.TOP)) {
                    StreamTopPanel(
                        state = streamState,
                        onHostSelected = streamViewModel::selectHost,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            /** The selected PC, its actions, and the address field. */
            val bottomContent: @Composable (Modifier) -> Unit = { bottomModifier ->
                Box(modifier = bottomModifier.claimsInputFor(Surface.BOTTOM)) {
                    if (couch) {
                        StreamCouchScreen(
                            state = streamState,
                            clientName = clientName,
                            onHostSelected = streamViewModel::selectHost,
                            onAddressChanged = streamViewModel::onAddressChanged,
                            onNameChanged = streamViewModel::onNameChanged,
                            onAddHost = streamViewModel::addTypedHost,
                            onOpenAddHost = streamViewModel::openAddHost,
                            onCloseAddHost = streamViewModel::closeAddHost,
                            onAddFieldFocused = streamViewModel::focusAddField,
                            onRefreshHost = streamViewModel::refresh,
                            onRefreshAll = streamViewModel::refreshAll,
                            onStartStream = streamViewModel::shareScreen,
                            onPairHost = streamViewModel::pair,
                            onCancelPairing = streamViewModel::cancelPairing,
                            onStopStream = streamViewModel::stopHostSession,
                            onForgetHost = streamViewModel::forget,
                            onOpenHelp = streamViewModel::openHelp,
                            onHelpSectionFocused = streamViewModel::focusHelpSection,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        StreamBottomPanel(
                            state = streamState,
                            clientName = clientName,
                            onAddressChanged = streamViewModel::onAddressChanged,
                            onAddHost = streamViewModel::addTypedHost,
                            onRefreshHost = streamViewModel::refresh,
                            onStartStream = streamViewModel::shareScreen,
                            onPairHost = streamViewModel::pair,
                            onCancelPairing = streamViewModel::cancelPairing,
                            onStopStream = streamViewModel::stopHostSession,
                            onForgetHost = streamViewModel::forget,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Settings and the keyboard are drawn over this panel, in that
                    // order: the keyboard is raised *from* the settings screen when
                    // a text row is opened, so it has to be on top of it.
                    if (settingsOpen) {
                        MoonlightSettingsScreen(
                            settings = settings,
                            controller = settingsController,
                            viewModel = settingsViewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (keyboardState.visible) {
                        ThorKeyboard(
                            text = keyboardState.text,
                            label = keyboardState.label,
                            layer = keyboardState.layer,
                            shifted = keyboardState.shifted,
                            cursorRow = keyboardState.cursorRow,
                            cursorColumn = keyboardState.cursorColumn,
                            onKey = { key ->
                                keyboard.onKey(key)
                                feedback.play(
                                    if (key is KeyboardKey.Character) {
                                        FeedbackCue.NAVIGATE
                                    } else {
                                        FeedbackCue.CONFIRM
                                    },
                                )
                            },
                            onDismiss = keyboard::close,
                            clips = keyboardState.clips.takeIf { keyboardState.clipboardOpen },
                            clipIndex = keyboardState.clipIndex,
                            onPasteClip = keyboard::pasteClip,
                            onCopyText = keyboard::copyFieldText,
                            compact = couch,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            /*
             * Which window holds the controller.
             *
             * The rule is the shared one: the window holding the active panel takes
             * focus, unless something else has taken it and the user has not asked
             * for it back. Couch mode draws both surfaces in the activity's window
             * and leaves the other panel dark, so the presentation must never take a
             * key there — which is exactly what `bothPanelsInActivityWindow` says.
             */
            val presentationTakesFocus: () -> Boolean = {
                LauncherFocus.presentationTakesFocus(
                    activePanel = when (touchedSurface) {
                        Surface.BOTTOM -> LauncherPanel.GRID
                        Surface.TOP -> LauncherPanel.INFO
                    },
                    gridInActivityWindow = bottomInActivityWindow,
                    overlayOpen = settingsOpen || keyboardState.visible,
                    focusYieldedToApp = focusYieldedToApp,
                    bothPanelsInActivityWindow = couch || mode != DualScreenMode.DUAL_DISPLAY,
                )
            }

            when (mode) {
                DualScreenMode.DUAL_DISPLAY -> {
                    if (bottomInActivityWindow) {
                        bottomContent(Modifier.fillMaxSize())
                        SecondaryDisplay(
                            displayId = secondaryDisplayId,
                            enabled = { true },
                            keepVisibleWhileStopped = false,
                            takesFocus = presentationTakesFocus,
                            keyDispatcher = inputRouter::dispatchKeyEvent,
                            motionDispatcher = inputRouter::onGenericMotionEvent,
                            onFocusChanged = { focused ->
                                // The only trace of a touch this window cannot see.
                                if (focused) focusYieldedToApp = false
                            },
                            content = { ThemedWindow(settings) { topContent() } },
                        )
                    } else {
                        topContent()
                        SecondaryDisplay(
                            displayId = secondaryDisplayId,
                            enabled = { true },
                            keepVisibleWhileStopped = false,
                            takesFocus = presentationTakesFocus,
                            keyDispatcher = inputRouter::dispatchKeyEvent,
                            motionDispatcher = inputRouter::onGenericMotionEvent,
                            onFocusChanged = { focused ->
                                if (focused) focusYieldedToApp = false
                            },
                            content = {
                                ThemedWindow(settings) { bottomContent(Modifier.fillMaxSize()) }
                            },
                        )
                    }
                }

                /*
                 * Couch mode: the television layout, on the top screen.
                 *
                 * Everything moves to one screen and the other panel is held black —
                 * not merely left blank, because a dismissed presentation lets the
                 * system fill that panel with its own wallpaper.
                 */
                DualScreenMode.COUCH -> {
                    bottomContent(Modifier.fillMaxSize())

                    if (secondaryDisplayId != null) {
                        SecondaryDisplay(
                            displayId = secondaryDisplayId,
                            enabled = { true },
                            keepVisibleWhileStopped = false,
                            takesFocus = { false },
                            content = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                                )
                            },
                        )
                    }
                }

                /*
                 * One screen, divided. The fallback when there is no second panel,
                 * and what the emulator and an ordinary phone get.
                 */
                DualScreenMode.SPLIT_SINGLE -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(settings.display.splitRatio.coerceIn(MIN_SPLIT, MAX_SPLIT)),
                        ) {
                            topContent()
                        }
                        bottomContent(
                            Modifier
                                .fillMaxWidth()
                                .weight(
                                    1f - settings.display.splitRatio
                                        .coerceIn(MIN_SPLIT, MAX_SPLIT),
                                ),
                        )
                    }
                }

                /*
                 * The bottom surface alone.
                 *
                 * The PC list becomes a sheet over it rather than disappearing: it is
                 * where the machines are, and a single-screen mode that could not
                 * show them would be a mode in which nothing can be chosen.
                 */
                DualScreenMode.SINGLE -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        bottomContent(Modifier.fillMaxSize())
                        if (touchedSurface == Surface.TOP) {
                            topContent()
                        }
                    }
                }

                // Resolved away by `effectiveMode`; it never reaches here.
                DualScreenMode.AUTO -> bottomContent(Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Re-applies the theme inside a presentation window.
 *
 * The composition local crosses the display boundary, but the *window* does not
 * inherit the background — a presentation opens on a transparent surface, and
 * without something opaque behind the panel the system's wallpaper shows through
 * wherever the content does not paint.
 */
@Composable
private fun ThemedWindow(
    settings: com.thor.core.model.ThorSettings,
    content: @Composable () -> Unit,
) {
    ThorTheme(
        personalization = settings.personalization,
        accessibility = settings.accessibility,
        performance = settings.performance,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ThorTheme.colors.background),
        ) {
            content()
        }
    }
}

/** What a press sounds and feels like, matching Loki's mapping exactly. */
private fun ControllerCommand.toCue(): FeedbackCue = when (this) {
    ControllerCommand.NAVIGATE_UP,
    ControllerCommand.NAVIGATE_DOWN,
    ControllerCommand.NAVIGATE_LEFT,
    ControllerCommand.NAVIGATE_RIGHT,
    -> FeedbackCue.NAVIGATE

    ControllerCommand.CONFIRM -> FeedbackCue.CONFIRM
    ControllerCommand.BACK, ControllerCommand.CANCEL_EDIT -> FeedbackCue.BACK
    else -> FeedbackCue.NAVIGATE
}

/** Bounds on the split, so neither surface can be squeezed out of existence. */
private const val MIN_SPLIT = 0.3f
private const val MAX_SPLIT = 0.7f
