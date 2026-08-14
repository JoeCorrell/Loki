package com.thor.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.thor.core.model.AccentSlot
import com.thor.core.model.LauncherExtension

/**
 * The settings navigation rail.
 *
 * Nine categories, down from seventeen and up from six. The original list
 * mirrored the feature breakdown rather than the way anyone actually looks for a
 * setting: separate pages for Themes and Personalization, for Networking and
 * Achievements, for Performance and Display, meant the rail needed scrolling and
 * every lookup became a guess about which of two plausible pages owned the
 * option.
 *
 * Collapsing that to six went one step too far in the other direction. Library
 * ended up holding eight pages — ROM folders beside icon packs beside a debrid
 * account — and two of them were about films, which are not a library of games
 * by any reading. A category is a promise about what is inside it, and "Library"
 * had stopped making one.
 *
 * These group by the question being asked: who am I, how does it look, where do
 * my games and their artwork live, what am I watching, how do I drive it, how
 * does it run, what is it.
 *
 * Home screen and Artwork have been split out and folded back in twice. The
 * argument for splitting is that six pages is a long list to walk; the argument
 * against is that a rail entry holding three is a stop on the way to somewhere
 * else, and the rail is walked far more often than any single category. Folded
 * in, with the rows drawn larger for the space it frees — which is the part that
 * makes the longer page lists bearable.
 */
enum class SettingsCategory(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val summary: String,
    /** Kept for stored/deep-linked ids even when its pages move into a clearer group. */
    val visible: Boolean = true,
    /**
     * The extension this category belongs to, if any.
     *
     * Absent from the rail entirely until that extension is enabled — see
     * [navigationEntries].
     */
    val extension: LauncherExtension? = null,
    /**
     * Which of the twelve wheel positions this category is painted with.
     *
     * A slot rather than a colour, so the tint is the *theme's* answer to
     * "green" rather than a hex value picked against one palette — see
     * [AccentSlot]. On a monochrome theme all twelve collapse onto the accent
     * and nothing here has to know.
     */
    val slot: AccentSlot = AccentSlot.SLATE,
    /**
     * The cluster of glyphs on the right of this category's hero.
     *
     * Three icons, drawn at different sizes and opacities to read as one piece
     * of art rather than as three buttons. Assembled from the icon set rather
     * than drawn as illustrations: an illustration per category is eleven
     * drawings to make and re-make every time a theme changes, and these tint
     * themselves.
     */
    val art: List<ImageVector> = emptyList(),
) {
    /**
     * Who is using the launcher, first because it contains all the rest.
     *
     * Every other category configures the *active* profile, so this is the one
     * that decides what the others are even editing. It was briefly a page under
     * System, which put the widest-reaching control in the launcher three rows
     * down a list of screen and performance options.
     */
    PROFILES(
        "profiles", "Profiles", Icons.Rounded.ManageAccounts,
        "Who is signed in, and their name and picture",
        slot = AccentSlot.BLUE,
        art = listOf(Icons.Rounded.Groups, Icons.Rounded.ManageAccounts, Icons.Rounded.Face),
    ),

    APPEARANCE(
        "appearance", "Personalization", Icons.Rounded.Palette,
        "Theme, surfaces, wallpaper and text",
        slot = AccentSlot.VIOLET,
        art = listOf(Icons.Rounded.Widgets, Icons.Rounded.Palette, Icons.Rounded.Contrast),
    ),

    HOME_SCREEN(
        "home", "Home screen", Icons.Rounded.GridView,
        "Grid layout, selection cursor and the dock",
        slot = AccentSlot.INDIGO,
        art = listOf(Icons.Rounded.Widgets, Icons.Rounded.GridView, Icons.Rounded.Dashboard),
    ),
    LIBRARY(
        "library", "Games", Icons.AutoMirrored.Rounded.LibraryBooks,
        "Platforms, ROM folders, scanning, sorting and smart folders",
        slot = AccentSlot.GREEN,
        art = listOf(Icons.Rounded.Folder, Icons.Rounded.SportsEsports, Icons.Rounded.Image),
    ),

    ARTWORK(
        "artwork", "Artwork", Icons.Rounded.Image,
        "Where descriptions and pictures come from, and achievements",
        slot = AccentSlot.LIME,
        art = listOf(Icons.Rounded.Download, Icons.Rounded.Image, Icons.Rounded.Palette),
    ),

    /**
     * Films and shows, which were filed under Library.
     *
     * A debrid token and a torrent indexer are not a library of games by any
     * reading, and putting them there meant the one category answered two
     * unrelated questions.
     */
    MOVIES(
        "movies", "Films & shows", Icons.Rounded.Movie,
        "Catalogue, sources and playback",
        extension = LauncherExtension.MOVIES,
        slot = AccentSlot.MAGENTA,
        art = listOf(Icons.Rounded.Theaters, Icons.Rounded.Movie, Icons.Rounded.PlayCircle),
    ),
    /**
     * Streaming a PC, which is neither a library nor a film.
     *
     * Its own category for the same reason Films & shows has one: it is a
     * section of the launcher with its own hardware at the other end, and the
     * questions it raises — how sharp, how smooth, how much bandwidth — have no
     * bearing on anything else here.
     */
    STREAMING(
        "streaming", "PC streaming", Icons.Rounded.Cast,
        "Picture quality and how PCs are found",
        extension = LauncherExtension.STREAM,
        slot = AccentSlot.CYAN,
        art = listOf(Icons.Rounded.Monitor, Icons.Rounded.Cast, Icons.Rounded.Wifi),
    ),
    CONTROLS(
        "controls", "Controls", Icons.Rounded.Gamepad,
        "Buttons, navigation, pointer, haptics and sound",
        slot = AccentSlot.RED,
        art = listOf(Icons.Rounded.TouchApp, Icons.Rounded.Gamepad, Icons.Rounded.Mouse),
    ),
    DISPLAY(
        "display", "Display & performance", Icons.Rounded.Monitor,
        "Dual-screen behaviour, visual effects and recording",
        slot = AccentSlot.ORANGE,
        art = listOf(Icons.Rounded.PhoneAndroid, Icons.Rounded.Monitor, Icons.Rounded.Speed),
    ),
    SYSTEM(
        "system", "System & accessibility", Icons.Rounded.Tune,
        "Accessibility, backup, network shares and extensions",
        slot = AccentSlot.TEAL,
        art = listOf(Icons.Rounded.Accessibility, Icons.Rounded.Tune, Icons.Rounded.Extension),
    ),
    ABOUT(
        "about", "About", Icons.Rounded.Info,
        "Version and device information",
        slot = AccentSlot.AMBER,
        art = listOf(Icons.Rounded.Storage, Icons.Rounded.Info, Icons.Rounded.Memory),
    ),
    ;

    companion object {
        /**
         * Categories the rail draws, for the extensions that are enabled.
         *
         * A category belonging to an extension the user has not asked for is not
         * shown empty or disabled — it is absent, along with every page under
         * it. Nothing in the base launcher should hint at a section that cannot
         * be opened.
         */
        fun navigationEntries(enabledExtensions: Set<String>): List<SettingsCategory> =
            entries.filter { it.visible && it.isAvailable(enabledExtensions) }

        private fun SettingsCategory.isAvailable(enabled: Set<String>): Boolean =
            extension?.id?.let { it in enabled } ?: true

        fun byId(id: String): SettingsCategory? = entries.firstOrNull { it.id == id }
    }
}
