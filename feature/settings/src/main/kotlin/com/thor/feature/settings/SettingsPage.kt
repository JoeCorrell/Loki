package com.thor.feature.settings

import com.thor.core.model.LauncherFeatures

/**
 * A single settings page.
 *
 * Settings is two levels: a category holds a short list of pages, and a page
 * holds the controls. Putting every control for a category on one surface meant
 * the Appearance pane alone ran to thirty-odd rows, which is unscannable and —
 * more practically — a very long way to travel with a D-pad. A page is small
 * enough to fit a screen, so opening one shows all of it at once.
 *
 * **Declaration order is display order** within a category, so the order here is
 * the order of the list the user reads. Pages are grouped by the question they
 * answer rather than by which part of the code owns them: `METADATA` sits with
 * icon packs because both are places artwork comes from, not with scanning
 * because both happen to be run by a sync manager.
 */
enum class SettingsPage(
    val category: SettingsCategory,
    val title: String,
    val summary: String,
    /**
     * The heading this page sits under, or null when the category needs none.
     *
     * Nothing uses one now, and the field stays because the mechanism is sound
     * and cheap: a heading is drawn rather than focused, so it costs the cursor
     * nothing and needs no arithmetic anywhere.
     *
     * It exists because Personalization and Games & artwork had each grown to
     * eight pages, and headings were tried as the alternative to splitting them.
     * Splitting won in the end. A heading tells you which half of a long list to
     * read; a rail entry means you never opened the wrong list at all — and the
     * rail is the thing being walked. Eleven entries of two to five pages is a
     * rail you scan and a list you take in whole.
     */
    val group: String? = null,
) {
    // ---- Personalization ---------------------------------------------------
    THEME(
        SettingsCategory.APPEARANCE, "Theme & colour",
        "The gallery, light or dark, accent, contrast and intensity",
    ),

    /**
     * What panels are *made of*, as opposed to what colour they are.
     *
     * Its own page rather than a section of the theme one, because it is the half
     * of a theme nobody could previously reach: the material, the corners, the
     * depth of the ground and the texture over it were all declared by whichever
     * palette was selected and overridable nowhere. Splitting them says the
     * quiet part out loud — a theme is a colour *and* a construction, and both are
     * yours.
     */
    SURFACES(
        SettingsCategory.APPEARANCE, "Surfaces",
        "Panel material, corners, depth and texture",
    ),

    /**
     * Building a theme, as opposed to choosing one and adjusting it.
     *
     * Third rather than first: the overwhelming majority of visits to
     * Personalization are somebody picking from the gallery, and a rail whose
     * opening row is an authoring tool tells them the easy thing is somewhere
     * further down. It sits directly under the two pages whose values it is the
     * source of, which is where somebody who has run out of adjustment will look.
     */
    THEME_EDITOR(
        SettingsCategory.APPEARANCE, "Theme editor",
        "Build a theme of your own, and share it",
    ),

    /*
     * The home screen's own three, together.
     *
     * They were scattered through Personalization between the theme pages and the
     * wallpaper — which is how "make the icons bigger" became a hunt. What the
     * grid holds, what the cursor on it looks like and what sits along its bottom
     * edge are one question asked three ways.
     */
    // Named for what it holds rather than for where it is: under a "Home screen"
    // heading, a page called "Home screen" says the heading twice and says nothing.
    GRID(
        SettingsCategory.HOME_SCREEN, "Grid & cards",
        "Which layout Home uses, then size, spacing, icons and labels",
    ),
    CURSOR(
        SettingsCategory.HOME_SCREEN, "Selection cursor",
        "Selection highlight style and glow",
    ),
    DOCK(
        SettingsCategory.HOME_SCREEN, "Dock",
        "Size, transparency and behaviour",
    ),

    WALLPAPER(
        SettingsCategory.APPEARANCE, "Wallpaper",
        "Background image, animated effect and how far it is dimmed",
    ),
    INTERFACE(
        SettingsCategory.APPEARANCE, "Interface",
        "Text size, motion, clock and folder style",
    ),

    // ---- Games & artwork ---------------------------------------------------
    PLATFORMS(
        SettingsCategory.LIBRARY, "Platforms",
        "Consoles, their ROM folders and emulators",
    ),
    ROM_FOLDERS(
        SettingsCategory.LIBRARY, "Extra ROM folders",
        "Locations not tied to one platform",
    ),
    SCANNING(
        SettingsCategory.LIBRARY, "Scanning",
        "How games and apps are found",
    ),

    SORTING(
        SettingsCategory.LIBRARY, "Sorting",
        "Default library order",
    ),

    /**
     * Folders defined by a query rather than by what was filed into them.
     *
     * Beside Sorting, because both answer "how is my library arranged" — and a
     * smart folder is closer to a saved sort than it is to the folders you make by
     * hand, which are made from the grid where they live.
     */
    SMART_FOLDERS(
        SettingsCategory.LIBRARY, "Smart folders",
        "Folders that fill themselves from a query",
    ),

    METADATA(
        SettingsCategory.ARTWORK, "Metadata & scraping",
        "Where descriptions and artwork are fetched from",
    ),
    ICON_PACKS(
        SettingsCategory.ARTWORK, "Platform artwork",
        "Icon packs, and the art Loki ships",
    ),

    /**
     * With artwork rather than with metadata, which it is not.
     *
     * Metadata describes the game; this describes the player's progress through
     * it. They come from different places, are keyed to different things, and one
     * of them needs an account. It was also declared at the very bottom of this
     * enum, which — since declaration order is display order — put it under
     * Accessibility's neighbours in the file and dead last in Games & artwork,
     * several pages away from everything it belongs with.
     */
    ACHIEVEMENTS(
        SettingsCategory.ARTWORK, "Achievements",
        "RetroAchievements account and matching",
    ),

    // ---- Films & shows -----------------------------------------------------
    MOVIES_CATALOGUE(
        SettingsCategory.MOVIES, "Sources & accounts",
        "Debrid account, addons and torrent indexers",
    ),
    MOVIES_PLAYBACK(
        SettingsCategory.MOVIES, "Playback",
        "Which source is chosen, and how it plays",
    ),

    /**
     * Trakt, on its own page rather than among the accounts.
     *
     * Sources & accounts is about what turns a title into a stream — a debrid
     * service, indexers, addons. Trakt touches none of that: it is a record of
     * what has been watched, and it is the only thing in the section that is
     * about the *viewer* rather than about the files. It also needs a page of its
     * own for a practical reason — signing in shows a code and waits, which is
     * not something to do halfway down a list of other people's API keys.
     */
    MOVIES_TRAKT(
        SettingsCategory.MOVIES, "Trakt",
        "Sync what you watch, and your watchlist",
    ),

    // ---- PC streaming ------------------------------------------------------
    STREAM_QUALITY(
        SettingsCategory.STREAMING, "Picture",
        "Resolution, frame rate and bandwidth",
    ),
    STREAM_CONTROLS(
        SettingsCategory.STREAMING, "Controls",
        "Trackpad, keyboard, touch and the pad",
    ),
    STREAM_HOSTS(
        SettingsCategory.STREAMING, "PCs",
        "How PCs are found, and what Loki calls itself",
    ),

    // ---- Controls ----------------------------------------------------------
    /**
     * First in Controls, because it is the one that decides what the rest mean.
     *
     * Navigation and Feedback tune how a press behaves; this decides which press
     * it was. It said so already and was declared second, which — since
     * declaration order is display order — meant it was drawn second and the
     * comment described an arrangement that did not exist.
     *
     * No group headings here: four pages is a list you read rather than search.
     */
    BUTTON_MAPPING(
        SettingsCategory.CONTROLS, "Button mapping",
        "Which button does what, and how it feels",
    ),
    NAVIGATION(
        SettingsCategory.CONTROLS, "Navigation",
        "Cursor movement and stick behaviour",
    ),
    POINTER(
        SettingsCategory.CONTROLS, "Pointer",
        "Controller mouse for apps and games",
    ),
    FEEDBACK(
        SettingsCategory.CONTROLS, "Feedback",
        "Haptics and interface sound",
    ),

    // ---- Profiles ----------------------------------------------------------
    // The list first, then the one profile you are actually signed in as. Whose
    // launcher this is comes before what it is called.
    PROFILES(
        SettingsCategory.PROFILES, "Profiles",
        "Switch, add and remove the people using this device",
    ),
    PROFILE_EDIT(
        SettingsCategory.PROFILES, "This profile",
        "Name, picture and colour for whoever is signed in",
    ),

    // ---- System ------------------------------------------------------------
    // Display and performance live here rather than in a category of their own.
    // Two pages is not a category, and "how the screens behave" is the same visit
    // as "how it reads and how hard it works".
    DUAL_SCREEN(
        SettingsCategory.DISPLAY, "Dual screen",
        "How the two panels are used",
    ),
    PERFORMANCE(
        SettingsCategory.DISPLAY, "Performance",
        "Animation and visual effects",
    ),
    ACCESSIBILITY(
        SettingsCategory.SYSTEM, "Accessibility",
        "Contrast, motion, text and colour vision",
    ),

    /**
     * Copying a profile out and putting it back.
     *
     * Under System rather than under Profiles: it is about the device and its
     * storage, and it is where somebody looks after deciding to reinstall — which
     * is the same visit as "how do I get my launcher back".
     */
    BACKUP(
        SettingsCategory.SYSTEM, "Backup",
        "Save this profile to a file, or restore one",
    ),

    /**
     * Servers the file explorer can reach, under System rather than Library.
     *
     * A share is somewhere the *device* can read, not somewhere games come from —
     * the explorer browses it and the scanner does not. Filing it beside Backup is
     * the honest grouping: both are about data that lives off this device.
     */
    NETWORK_SHARES(
        SettingsCategory.SYSTEM, "Network shares",
        "Browse a NAS or a PC's shared folders",
    ),

    EXTENSIONS(
        SettingsCategory.SYSTEM, "Extensions",
        "Add Films & shows or PC streaming to the launcher",
    ),

    /**
     * What a recording captures, as opposed to when one is started.
     *
     * Starting is two tiles in the shortcut panel and one on the companion panel,
     * where the decision is actually made; this is the standing choice those
     * inherit. Under Data & features because a recording is a file, and beside
     * Backup because both are about what leaves the device.
     */
    RECORDING(
        SettingsCategory.DISPLAY, "Recording",
        "Whether captures have sound",
    ),
    ;

    companion object {
        /**
         * Pages belonging to [category], in declaration order.
         *
         * Pages for a hidden feature are left out entirely rather than shown
         * disabled: a settings page whose controls reach nothing on screen is
         * worse than a missing one, because the user changes a value, sees no
         * effect, and has no way to tell a dead page from a broken setting. The
         * page itself is kept — see [LauncherFeatures] — so restoring the feature
         * restores its configuration with it.
         */
        fun forCategory(
            category: SettingsCategory,
            enabledExtensions: Set<String> = emptySet(),
        ): List<SettingsPage> = entries.filter {
            it.category == category && it.isAvailable && it.isUnlocked(enabledExtensions)
        }

        private val SettingsPage.isAvailable: Boolean
            get() = when (this) {
                DOCK -> LauncherFeatures.DOCK_ENABLED
                else -> true
            }

        /**
         * Whether the extension this page's category belongs to is enabled.
         *
         * The same question [SettingsCategory] answers for the rail, asked again
         * here — not a finer-grained one. It used to claim to be per *page*, on
         * the reasoning that a page could belong to an extension its category
         * does not; nothing supports that, because [SettingsPage] carries no
         * extension of its own and this reads `category.extension`. A page in
         * that position would still be listed.
         *
         * Worth keeping as the second gate even so. The rail cannot offer a
         * hidden category, but the *selected* one is held in the view model and
         * outlives the rail that chose it: removing an extension while its
         * category is open leaves a selection naming something no longer there,
         * and this is what stops its pages being handed back.
         */
        private fun SettingsPage.isUnlocked(enabled: Set<String>): Boolean =
            category.extension?.id?.let { it in enabled } ?: true
    }
}
