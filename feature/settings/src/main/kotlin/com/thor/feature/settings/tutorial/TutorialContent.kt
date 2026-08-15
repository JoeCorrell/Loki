package com.thor.feature.settings.tutorial

import com.thor.core.model.ControllerCommand
import com.thor.core.model.LauncherExtension
import com.thor.core.model.LauncherTab
import com.thor.feature.settings.SettingsCategory

/** Which display carries a walkthrough card. */
enum class TutorialPanel { GRID, INFO }

/** A structural region that the coach mark leaves visible. */
enum class TutorialSpot { NONE, GRID, NAV_BAR, PANEL }

/**
 * One lesson in the interactive walkthrough.
 *
 * [detailPoints] keep the important rules scannable, while [practice] turns the
 * lesson into a safe simulation. No practice command is sent to the live grid.
 */
data class TutorialStep(
    val title: String,
    val body: String,
    val chapter: String = "Learn Loki",
    val detailPoints: List<String> = emptyList(),
    val hint: String? = null,
    val panel: TutorialPanel = TutorialPanel.GRID,
    val spot: TutorialSpot = TutorialSpot.NONE,
    val demo: TutorialDemo = TutorialDemo.DUAL_SCREEN,
    val practice: TutorialPractice? = null,
    val settingsCategory: SettingsCategory? = null,
)

/** Builds the first-run tour from the features and settings that actually exist. */
object ThorTutorial {

    fun base(enabledExtensions: Set<String>): List<TutorialStep> = buildList {
        add(
            TutorialStep(
                chapter = "Welcome",
                title = "Meet your two-screen home",
                body = "Loki keeps selection and controls on the display in your hands, " +
                    "while the other display turns that selection into a useful detail view.",
                detailPoints = listOf(
                    "The highlighted card controls the art and information on the other screen.",
                    "Games can launch on either display without changing where Home lives.",
                    "Every lesson is safe: the practice area never launches or edits real items.",
                ),
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.PANEL,
                demo = TutorialDemo.DUAL_SCREEN,
            ),
        )
        add(
            TutorialStep(
                chapter = "Controller basics",
                title = "Move around the grid",
                body = "Use the D-pad or left stick. Try every direction below; the sample " +
                    "cursor moves, but your real Home selection stays untouched.",
                detailPoints = listOf(
                    "Holding a direction repeats after a short delay.",
                    "Navigation follows your active controller profile and remapped buttons.",
                ),
                hint = "Complete all four controls to continue",
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.GRID,
                demo = TutorialDemo.NAVIGATION,
                practice = TutorialPractice(
                    title = "Move the sample cursor",
                    tasks = listOf(
                        TutorialTask(ControllerCommand.NAVIGATE_UP, "Move up"),
                        TutorialTask(ControllerCommand.NAVIGATE_DOWN, "Move down"),
                        TutorialTask(ControllerCommand.NAVIGATE_LEFT, "Move left"),
                        TutorialTask(ControllerCommand.NAVIGATE_RIGHT, "Move right"),
                    ),
                ),
            ),
        )
        add(
            TutorialStep(
                chapter = "Controller basics",
                title = "Open something, then come back",
                body = "Confirm opens the selected game, app, folder, or action. Back closes " +
                    "the current layer before it ever leaves the launcher.",
                detailPoints = listOf(
                    "The practice launch is only an animation; no application will open.",
                    "Home always returns to Loki's resting Home screen.",
                ),
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.GRID,
                demo = TutorialDemo.LAUNCH_AND_BACK,
                practice = TutorialPractice(
                    title = "Practice opening and returning",
                    tasks = listOf(
                        TutorialTask(ControllerCommand.CONFIRM, "Open sample"),
                        TutorialTask(ControllerCommand.BACK, "Return"),
                    ),
                ),
            ),
        )
        add(
            TutorialStep(
                chapter = "Your library",
                title = "Favorite and inspect anything",
                body = "Favorite is the quickest way to build a personal shelf. Context opens " +
                    "the complete action menu for the current game, app, folder, or file.",
                detailPoints = listOf(
                    "Context actions include edit, artwork, placement, emulator, hide, and remove.",
                    "Removing a launcher entry never deletes its ROM unless an action says so explicitly.",
                ),
                hint = "These actions affect only the sample card",
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.GRID,
                demo = TutorialDemo.LIBRARY_ACTIONS,
                practice = TutorialPractice(
                    title = "Try two common actions",
                    tasks = listOf(
                        TutorialTask(ControllerCommand.TOGGLE_FAVORITE, "Toggle favorite"),
                        TutorialTask(ControllerCommand.CONTEXT_MENU, "Open actions"),
                    ),
                ),
            ),
        )
        add(
            TutorialStep(
                chapter = "Your library",
                title = "Pages and artwork",
                body = "Page controls move through Home without walking every cell. Image controls " +
                    "cycle the selected game's screenshots on the information display.",
                detailPoints = listOf(
                    "Pinch the grid to choose one of eight density presets.",
                    "Empty cells remain empty and icons keep their chosen positions.",
                ),
                panel = TutorialPanel.INFO,
                spot = TutorialSpot.PANEL,
                demo = TutorialDemo.MEDIA_BROWSING,
                practice = TutorialPractice(
                    title = "Turn a page and change the image",
                    tasks = listOf(
                        TutorialTask(ControllerCommand.PAGE_PREVIOUS, "Previous page"),
                        TutorialTask(ControllerCommand.PAGE_NEXT, "Next page"),
                        TutorialTask(ControllerCommand.CYCLE_IMAGE_PREVIOUS, "Previous image"),
                        TutorialTask(ControllerCommand.CYCLE_IMAGE_NEXT, "Next image"),
                    ),
                ),
            ),
        )
        add(
            TutorialStep(
                chapter = "Your library",
                title = "Arrange without reflow",
                body = "Hold Confirm to pick up a card, move it, then Confirm again to drop it. " +
                    "Cancel leaves the original placement intact.",
                detailPoints = listOf(
                    "Folders keep large systems from taking over the Home grid.",
                    "Sparse layouts and intentionally empty cells are preserved.",
                ),
                hint = "This lesson never changes your real layout",
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.GRID,
                demo = TutorialDemo.ARRANGE,
                practice = TutorialPractice(
                    title = "Pick up, then cancel safely",
                    tasks = listOf(
                        TutorialTask(ControllerCommand.PICK_UP, "Pick up sample"),
                        TutorialTask(ControllerCommand.BACK, "Cancel move"),
                    ),
                ),
            ),
        )

        val sections = LauncherTab.visible(enabledExtensions)
        if (sections.size > 1) {
            add(
                TutorialStep(
                    chapter = "Sections",
                    title = "Switch from the bottom rail",
                    body = "Press Down past the final grid row to reach the section rail, then move " +
                        "Left or Right between ${sections.joinToString(", ") { it.label }}.",
                    detailPoints = listOf(
                        "The rail appears only when an enabled extension adds another section.",
                        "Returning Home restores the Home section and first page.",
                    ),
                    panel = TutorialPanel.GRID,
                    spot = TutorialSpot.NAV_BAR,
                    demo = TutorialDemo.NAVIGATION,
                ),
            )
        }

        add(
            TutorialStep(
                chapter = "Fast access",
                title = "Open Loki's three utility layers",
                body = "The side menu changes sections, the app drawer reaches installed apps, and " +
                    "Shortcuts exposes search, settings, scan, files, recording, and more.",
                detailPoints = listOf(
                    "The labels below use logical actions, so they stay correct after remapping.",
                    "Press Back to close whichever layer is currently on top.",
                ),
                panel = TutorialPanel.GRID,
                demo = TutorialDemo.SHELL_TOOLS,
                practice = TutorialPractice(
                    title = "Open each simulated layer",
                    tasks = listOf(
                        TutorialTask(ControllerCommand.OPEN_SIDE_MENU, "Side menu"),
                        TutorialTask(ControllerCommand.OPEN_APP_DRAWER, "App drawer"),
                        TutorialTask(ControllerCommand.OPEN_SHORTCUTS, "Shortcuts"),
                    ),
                ),
            ),
        )
        add(
            TutorialStep(
                chapter = "Beyond the launcher",
                title = "Point and type on either screen",
                body = "The accessibility service enables Loki's controller pointer and cross-app " +
                    "typing for standard Android text fields.",
                detailPoints = listOf(
                    "Hold Start + Select to show the pointer; the left stick moves and the right stick scrolls.",
                    "Use the pointer actions mapped under Controller & input for click, keyboard, and other tools.",
                    "Android must show the service as On before these controls work outside Loki.",
                ),
                panel = TutorialPanel.INFO,
                demo = TutorialDemo.POINTER,
            ),
        )
        add(
            TutorialStep(
                chapter = "Files and sharing",
                title = "Move files with a safety net",
                body = "Files can browse local storage and SMB shares, copy or move folders, and " +
                    "create or extract archives without publishing incomplete output.",
                detailPoints = listOf(
                    "Transfers write to staging, verify byte counts and SHA-256, then publish.",
                    "An interrupted move keeps the source until the destination is verified.",
                    "Opening Network shares automatically discovers SMB devices on the local network.",
                ),
                panel = TutorialPanel.GRID,
                demo = TutorialDemo.DUAL_SCREEN,
                settingsCategory = SettingsCategory.SYSTEM,
            ),
        )
        add(
            TutorialStep(
                chapter = "Capture and companion",
                title = "Keep tools beside a running game",
                body = "The companion panel holds notes, screenshots, pointer and keyboard controls. " +
                    "Recording can capture outside the launcher after Android grants screen capture.",
                detailPoints = listOf(
                    "Real-display capture follows both physical displays when Android exposes them.",
                    "Launcher-composite recording captures Loki itself, not an app drawn over it.",
                    "Recording audio is microphone or room audio, not clean internal app audio.",
                ),
                panel = TutorialPanel.GRID,
                demo = TutorialDemo.DUAL_SCREEN,
                settingsCategory = SettingsCategory.DISPLAY,
            ),
        )

        addAll(settingsSteps(enabledExtensions))

        add(
            TutorialStep(
                chapter = "Ready",
                title = "Loki is yours",
                body = "Add systems and ROM folders, choose how the two displays behave, then make " +
                    "the Home screen as sparse or as dense as you like.",
                detailPoints = listOf(
                    "Background scanning, scraping and playtime tracking continue while you browse.",
                    "Replay this interactive walkthrough from Settings → About at any time.",
                    "Nothing in this tour changed your library, layout, or files.",
                ),
                hint = "Press Done to return Home",
                panel = TutorialPanel.GRID,
                demo = TutorialDemo.READY,
            ),
        )
    }

    /** A focused tour shown the first time an optional extension is enabled. */
    fun forExtension(extension: LauncherExtension): List<TutorialStep> = when (extension) {
        LauncherExtension.MOVIES -> listOf(
            TutorialStep(
                chapter = "New extension",
                title = "Movies is now on the section rail",
                body = "Browse films and shows, inspect a title on the other display, resolve a " +
                    "source, and play without leaving Loki.",
                detailPoints = listOf(
                    "Browsing and playback sources are configured independently.",
                    "Removing the extension hides its UI but retains its settings.",
                ),
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.NAV_BAR,
            ),
            TutorialStep(
                chapter = "New extension",
                title = "Connect a playback source",
                body = "Add a Stremio-compatible URL or Torznab indexer, then optionally connect a " +
                    "debrid provider for more reliable source resolution.",
                detailPoints = listOf(
                    "Filters and playback behavior live in Films & shows.",
                    "Source browsing requires a network connection.",
                ),
                panel = TutorialPanel.GRID,
                demo = TutorialDemo.SETTINGS,
                settingsCategory = SettingsCategory.MOVIES,
            ),
        )

        LauncherExtension.STREAM -> listOf(
            TutorialStep(
                chapter = "New extension",
                title = "PC streaming is now on the rail",
                body = "Loki discovers Sunshine hosts, pairs with a PIN, and streams video, audio, " +
                    "controller input, and configured apps.",
                detailPoints = listOf(
                    "Hosts can also be entered manually when discovery is unavailable.",
                    "Resolution, frame rate, bitrate, codec and audio are configurable.",
                ),
                panel = TutorialPanel.GRID,
                spot = TutorialSpot.NAV_BAR,
            ),
            TutorialStep(
                chapter = "New extension",
                title = "The second screen stays useful",
                body = "During a stream, the companion display can act as a trackpad and keyboard " +
                    "for the remote computer.",
                detailPoints = listOf(
                    "Pairing and picture quality live in PC streaming settings.",
                    "Back exits a stream; the emergency chord is Start + Select + L1 + R1.",
                ),
                panel = TutorialPanel.GRID,
                demo = TutorialDemo.SETTINGS,
                settingsCategory = SettingsCategory.STREAMING,
            ),
        )
    }

    private fun settingsSteps(enabledExtensions: Set<String>): List<TutorialStep> {
        val categories = SettingsCategory.navigationEntries(enabledExtensions)
        return buildList {
            add(
                TutorialStep(
                    chapter = "Settings tour",
                    title = "Settings opens on the other display",
                    body = "The category rail stays short; each category contains a few focused " +
                        "pages. The next lessons open the real category while explaining it here.",
                    detailPoints = listOf(
                        "Use Up and Down to choose a category, Confirm to enter, and Back to return.",
                        "Settings are saved as soon as they change.",
                    ),
                    hint = "Shortcuts → Settings opens this from anywhere in Loki",
                    panel = TutorialPanel.GRID,
                    demo = TutorialDemo.SETTINGS,
                    settingsCategory = categories.firstOrNull(),
                ),
            )
            categories.forEach { category ->
                val copy = CATEGORY_COPY[category]
                add(
                    TutorialStep(
                        chapter = "Settings tour",
                        title = category.title,
                        body = copy?.first ?: category.summary,
                        detailPoints = copy?.second.orEmpty(),
                        panel = TutorialPanel.GRID,
                        demo = TutorialDemo.SETTINGS,
                        settingsCategory = category,
                    ),
                )
            }
        }
    }

    private val CATEGORY_COPY: Map<SettingsCategory, Pair<String, List<String>>> = mapOf(
        SettingsCategory.PROFILES to (
            "Keep libraries, layouts, play history, preferences, and artwork choices separate for each player." to
                listOf("Create or switch profiles here.", "Backup and restore always target the active profile.")
            ),
        SettingsCategory.APPEARANCE to (
            "Choose among twenty-three generated light and dark themes, surface materials, wallpaper, text, clock, and motion." to
                listOf("The theme editor can export and import visual themes.", "Reduced motion is also available under accessibility.")
            ),
        SettingsCategory.HOME_SCREEN to (
            "Shape the grid, platform cards, cursor, density, dock, and native widgets without rebuilding your library." to
                listOf("Grid mode supports sparse placement and widgets.", "Platform-card mode preserves widgets but does not display them.")
            ),
        SettingsCategory.LIBRARY to (
            "Add systems, emulators and ROM folders, then control scanning, sorting, grouping, smart folders, and missing files." to
                listOf("Archive and CHD formats are recognized during scans.", "Scans run in the background and preserve missing entries.")
            ),
        SettingsCategory.ARTWORK to (
            "Configure descriptions, artwork, achievements, icon packs, manual matches, and provider credentials." to
                listOf("Wikidata works without a key; other providers may require one.", "Manual art always remains available when automatic matching misses.")
            ),
        SettingsCategory.MOVIES to (
            "Configure the catalogue, source providers, debrid accounts, filters, Trakt, and playback behavior." to
                listOf("Imported extension files enable built-in code; they do not download executable plugins.")
            ),
        SettingsCategory.STREAMING to (
            "Pair Sunshine hosts and tune resolution, frame rate, bitrate, codec, audio, controller and discovery settings." to
                listOf("Automatic discovery and manual host entry can be used together.")
            ),
        SettingsCategory.CONTROLS to (
            "Remap every logical action, tune navigation repeat and pointer behavior, and choose haptic and sound feedback." to
                listOf("Button tester shows the raw control Loki receives.", "Profiles let different controllers keep different mappings.")
            ),
        SettingsCategory.DISPLAY to (
            "Choose automatic, dual-display, split-single, single-screen or couch behavior, then tune effects, performance, and recording." to
                listOf("Per-launch display targets can override the general mode.", "Couch UI scale is independent from handheld text scale.")
            ),
        SettingsCategory.SYSTEM to (
            "Accessibility, profile backup, automatic network-share discovery, saved SMB servers, and optional extensions live here." to
                listOf("Network shares scan when the page opens.", "Accessibility service status is shown before you leave Loki.")
            ),
        SettingsCategory.ABOUT to (
            "Check build and device details, make Loki the default Home app, test buttons, enable diagnostics, reset settings, or replay this tour." to
                listOf("Replay interactive walkthrough starts immediately and returns Home.", "Reset settings does not delete library data.")
            ),
    )
}
