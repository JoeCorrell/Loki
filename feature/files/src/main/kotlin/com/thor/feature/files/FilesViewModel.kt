package com.thor.feature.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.thor.core.common.log.ThorLog
import com.thor.core.model.ControllerCommand
import com.thor.core.model.FileEntry
import com.thor.core.model.FileKind
import com.thor.core.model.FileSort
import com.thor.core.model.breadcrumbs
import com.thor.core.model.childPathOf
import com.thor.core.model.fileNameOf
import com.thor.core.model.isRemotePath
import com.thor.core.model.parentPathOf
import com.thor.core.model.siblingPath
import com.thor.core.model.sortFiles
import com.thor.data.files.FileListing
import com.thor.data.files.FileRepository
import com.thor.data.files.FileResult
import com.thor.data.files.FileShortcut
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * The file explorer.
 *
 * Holds one cursor over one directory and answers the controller. The two panels
 * are both views of [FilesUiState] — there is no second cursor on the information
 * panel to keep in step, because that panel does not have one.
 *
 * Ordinary Android intents do the opening. A launcher that shipped its own image
 * viewer, text editor and media player would be maintaining four half-applications
 * to avoid asking the device what it already has.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: FileRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    /**
     * The listing in flight, cancelled when a new one starts.
     *
     * A directory of ten thousand ROMs takes long enough that the user can have
     * moved on before it finishes, and an unguarded second listing would race the
     * first — with the *older* one sometimes landing last and replacing the folder
     * they had just opened.
     */
    private var listingJob: Job? = null
    private var transferJob: Job? = null

    /** Whether the explorer has ever been opened, so it re-reads rather than reloads. */
    private var started = false

    // ---- Opening -----------------------------------------------------------

    /**
     * Called each time the explorer is shown.
     *
     * Re-reads the current directory rather than resetting to the top: coming back
     * to the explorer should find it where it was left. The permission is re-checked
     * every time, because granting it happens in an Android screen the launcher is
     * not on — returning here is the only moment the answer can have changed.
     */
    fun onShown() {
        /*
         * Opened fresh, whatever state it was closed in.
         *
         * The directory is deliberately kept — coming back should find the
         * explorer where it was left — but the cursor's *pane* and any half
         * answered prompt are not part of "where you were". Reopening onto the
         * button bar, or onto a delete confirmation for a file chosen minutes
         * ago in another folder, is the launcher acting on a question the user
         * has forgotten asking.
         */
        _state.update { it.copy(pane = FilesPane.LISTING, prompt = null, message = null) }

        viewModelScope.launch {
            /*
             * The rail is rebuilt every time, not only on the first open.
             *
             * It used to be read once and then only if it had come back empty,
             * which was fine while it listed volumes — those do not appear while
             * the launcher is running. Network shares do: they are added in
             * Settings, which is somewhere the user goes *and comes back from*,
             * and a rail that only refreshed on a cold start would leave a server
             * they had just set up invisible until the launcher was restarted.
             *
             * It costs a settings read and a directory scan of `/storage`, both of
             * which are already paid for on the way in.
             */
            _state.update { it.copy(shortcuts = repository.shortcuts()) }

            if (!started) {
                started = true
                navigateTo(repository.defaultDirectory())
            } else {
                reload()
            }
        }
    }

    /**
     * Opens a directory.
     *
     * @param focusOn the path to leave the cursor on once the listing lands. Used
     *   when going *up* a level, so the folder just left is the row under the
     *   cursor rather than starting each level again from the top.
     */
    fun navigateTo(path: String, focusOn: String? = null) {
        listingJob?.cancel()
        listingJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    path = path,
                    crumbs = breadcrumbs(path),
                    status = FilesStatus.Loading,
                    /*
                     * Marks are *kept* across a change of folder.
                     *
                     * They used to be dropped here, on the reasoning that a mark
                     * belongs to the folder it was made in and carrying it across
                     * risks a delete acting on files nobody can see. The risk is
                     * real; clearing them was still the wrong answer, because it
                     * left no way to do the obvious thing — tick some files, walk
                     * to where they should go, and move them. That is what marking
                     * is *for*, and without it the feature only ever worked on
                     * whatever happened to share one directory.
                     *
                     * The risk is handled where it actually lives instead:
                     * [FilesUiState.markedElsewhere] counts what is out of sight
                     * and every surface that acts on the set says so.
                     */
                    cursor = 0,
                    message = null,
                )
            }
            applyListing(repository.list(path), keepCursorOn = focusOn)
            _state.update { it.copy(space = repository.volumeSpace(path)) }
        }
    }

    /** Re-reads the current directory, keeping the cursor on the same file. */
    private fun reload(keepCursorOn: String? = _state.value.focused?.path) {
        listingJob?.cancel()
        listingJob = viewModelScope.launch {
            val path = _state.value.path
            applyListing(repository.list(path), keepCursorOn)
            _state.update { it.copy(space = repository.volumeSpace(path)) }
        }
    }

    private fun applyListing(listing: FileListing, keepCursorOn: String?) {
        when (listing) {
            is FileListing.Loaded -> _state.update { current ->
                val ordered = sortFiles(
                    entries = listing.entries,
                    sort = current.sort,
                    descending = current.descending,
                    showHidden = current.showHidden,
                )
                current.copy(
                    entries = ordered,
                    cursor = restoreCursor(ordered, keepCursorOn, current.cursor),
                    status = FilesStatus.Ready,
                    marked = current.marked.pruneAgainst(current.path, ordered),
                )
            }

            FileListing.NoAccess -> _state.update {
                it.copy(entries = emptyList(), status = FilesStatus.NoAccess)
            }

            FileListing.Missing -> _state.update {
                it.copy(
                    entries = emptyList(),
                    status = FilesStatus.Problem("That folder is no longer there"),
                )
            }

            FileListing.Unreadable -> _state.update {
                it.copy(
                    entries = emptyList(),
                    status = FilesStatus.Problem("Android will not let this folder be read"),
                )
            }

            /*
             * A share that did not answer, carrying the server's own reason.
             *
             * Kept distinct from [FileListing.Unreadable] all the way to the
             * screen because the next step differs: a permission problem is fixed
             * in Android's settings, and this one is fixed by waking the NAS,
             * joining the right network, or correcting a password. Flattening
             * both into "cannot read" would send the user to the wrong place.
             */
            is FileListing.Offline -> _state.update {
                it.copy(entries = emptyList(), status = FilesStatus.Problem(listing.reason))
            }
        }
    }

    /**
     * Drops marks that this listing proves are gone, and only those.
     *
     * A mark on a file that has been deleted or moved out from under it would
     * silently widen the next action's targets to include a path that is not
     * there. But "not in this listing" is only evidence of that for files *in
     * this folder* — everything ticked somewhere else is legitimately absent, and
     * an intersection against the current directory was quietly deleting exactly
     * the marks the user had walked here to act on.
     */
    private fun Set<String>.pruneAgainst(path: String, listing: List<FileEntry>): Set<String> {
        if (isEmpty()) return this
        val here = listing.mapTo(HashSet(), FileEntry::path)
        return filterTo(LinkedHashSet()) { marked ->
            parentPathOf(marked) != path || marked in here
        }
    }

    // ---- The controller ----------------------------------------------------

    /**
     * One press.
     *
     * Returns what happened so the shell can play the matching cue and close the
     * overlay when the explorer is done with it — the explorer does not know it is
     * an overlay, and the shell does not know what a directory is.
     */
    fun onCommand(command: ControllerCommand, accelerated: Boolean): FilesOutcome {
        val current = _state.value

        // Nothing may be acted on over a copy in flight. The listing underneath is
        // about to change and a press against it would act on a stale row.
        if (current.isBusy) {
            return if (command == ControllerCommand.BACK) {
                cancelTransfer()
                FilesOutcome.BACK
            } else {
                FilesOutcome.REJECTED
            }
        }

        current.prompt?.let { return onPromptCommand(it, command) }
        if (current.pane == FilesPane.ACTIONS) return onActionPaneCommand(current, command)

        return when (command) {
            ControllerCommand.NAVIGATE_UP -> step(-1, accelerated)
            ControllerCommand.NAVIGATE_DOWN -> step(1, accelerated)
            ControllerCommand.PAGE_PREVIOUS -> step(-PAGE_STEP, accelerated = false)
            ControllerCommand.PAGE_NEXT -> step(PAGE_STEP, accelerated = false)

            // The rail is to the left of the listing on screen, so it is to the
            // left of it on the stick.
            ControllerCommand.NAVIGATE_LEFT -> if (current.pane == FilesPane.LISTING) {
                _state.update { it.copy(pane = FilesPane.SHORTCUTS) }
                FilesOutcome.MOVED
            } else {
                FilesOutcome.REJECTED
            }

            ControllerCommand.NAVIGATE_RIGHT -> if (current.pane == FilesPane.SHORTCUTS) {
                _state.update { it.copy(pane = FilesPane.LISTING) }
                FilesOutcome.MOVED
            } else {
                FilesOutcome.REJECTED
            }

            ControllerCommand.CONFIRM -> confirm()
            ControllerCommand.BACK -> back()

            /*
             * Down to the other screen.
             *
             * This used to raise a menu of the same verbs. It does not need to any
             * more — they are all on the bottom panel already — so the button that
             * meant "show me the actions" now means "go to them", which is the
             * same intention with the modal step taken out.
             */
            ControllerCommand.CONTEXT_MENU -> enterActions()

            // The same button that favourites a game marks a file: both are "this
            // one, for later".
            ControllerCommand.TOGGLE_FAVORITE -> {
                toggleMark()
                FilesOutcome.ACTED
            }

            ControllerCommand.CYCLE_IMAGE_PREVIOUS -> {
                cycleSort(-1)
                FilesOutcome.ACTED
            }

            ControllerCommand.CYCLE_IMAGE_NEXT -> {
                cycleSort(1)
                FilesOutcome.ACTED
            }

            else -> FilesOutcome.REJECTED
        }
    }

    private fun step(delta: Int, accelerated: Boolean): FilesOutcome {
        val current = _state.value
        // Held direction runs faster through a long directory; a thousand-row
        // folder at one row a press is not navigation.
        val distance = if (accelerated) delta * ACCELERATED_STEP else delta

        return when (current.pane) {
            FilesPane.SHORTCUTS -> {
                val next = moveCursor(current.shortcutCursor, delta, current.shortcuts.size)
                if (next == current.shortcutCursor) return FilesOutcome.REJECTED
                _state.update { it.copy(shortcutCursor = next) }
                FilesOutcome.MOVED
            }

            FilesPane.LISTING -> {
                val next = moveCursor(current.cursor, distance, current.entries.size)
                if (next == current.cursor) return FilesOutcome.REJECTED
                _state.update { it.copy(cursor = next) }
                FilesOutcome.MOVED
            }

            /*
             * Unreachable, and named rather than folded into an `else`.
             *
             * The action pane takes its own presses in [onActionPaneCommand] and
             * returns before this is called. An `else` here would silently move
             * the *listing's* cursor from the button panel the first time that
             * ordering was disturbed — the file under the cursor changing while
             * the user is looking at the buttons, which is how a delete lands on
             * the wrong thing. This way the compiler asks.
             */
            FilesPane.ACTIONS -> FilesOutcome.REJECTED
        }
    }

    private fun confirm(): FilesOutcome {
        val current = _state.value

        if (current.pane == FilesPane.SHORTCUTS) {
            val shortcut = current.focusedShortcut ?: return FilesOutcome.REJECTED
            navigateTo(shortcut.path)
            // Back to the listing, because the point of picking a place is to look
            // at what is in it.
            _state.update { it.copy(pane = FilesPane.LISTING) }
            return FilesOutcome.ACTED
        }

        val entry = current.focused ?: return FilesOutcome.REJECTED
        return if (entry.isDirectory) {
            navigateTo(entry.path)
            FilesOutcome.ACTED
        } else {
            openExternally(entry)
        }
    }

    /**
     * Back, unwinding one thing at a time.
     *
     * Marks first, then the rail, then the directory tree, and only then the
     * explorer itself. Closing outright from four levels down would lose the place
     * the user had walked to, and clearing marks on the way out would be silent.
     */
    private fun back(): FilesOutcome {
        val current = _state.value

        if (current.pane == FilesPane.SHORTCUTS) {
            _state.update { it.copy(pane = FilesPane.LISTING) }
            return FilesOutcome.BACK
        }

        if (current.marked.isNotEmpty()) {
            _state.update { it.copy(marked = emptySet()) }
            return FilesOutcome.BACK
        }

        val parent = repository.parentOf(current.path)
        return if (parent != null) {
            // The folder just left is where the cursor lands, so walking back up a
            // tree does not start each level from the top again.
            navigateTo(parent, focusOn = current.path)
            FilesOutcome.BACK
        } else {
            FilesOutcome.CLOSE
        }
    }

    // ---- The action panel --------------------------------------------------

    /**
     * Moves the controller to the buttons on the bottom screen.
     *
     * Lands on the first button that can actually do something rather than on
     * the first button, so arriving does not require walking past a row of greyed
     * out verbs to reach the one that applies.
     */
    fun enterActions(): FilesOutcome {
        val current = _state.value
        val first = FileAction.entries.indexOfFirst { it.isEnabled(current) }
        if (first < 0) return FilesOutcome.REJECTED

        _state.update {
            it.copy(
                pane = FilesPane.ACTIONS,
                actionCursor = if (it.focusedAction?.isEnabled(current) == true) {
                    it.actionCursor
                } else {
                    first
                },
                message = null,
            )
        }
        return FilesOutcome.MOVED
    }

    fun leaveActions() {
        _state.update { it.copy(pane = FilesPane.LISTING) }
    }

    fun setActionLayout(layout: FilesActionLayout) {
        _state.update { it.copy(actionLayout = layout) }
    }

    /**
     * Reports progress only when it has visibly moved.
     *
     * The repository counts bytes and calls back on every 64 KB chunk, which for
     * a gigabyte is sixteen thousand callbacks. Each one wrote a new state, and
     * every state emission recomposes both panels — so moving a large file
     * spent nearly all of its time drawing a progress bar instead of copying,
     * and on a big enough file the recomposition backlog and the garbage from
     * sixteen thousand discarded states took the launcher down with it. That is
     * the "slow, then crashed" this replaces.
     *
     * A bar on a handheld screen has a few hundred usable positions, so anything
     * finer than one percent is invisible by construction. The final callback
     * always lands, whatever its size, so the bar reaches its end rather than
     * stopping at ninety-nine.
     */
    private fun throttledProgress(
        label: String,
    ): (Long, Long) -> Unit {
        var lastReported = -1L
        return { done, total ->
            val step = (total / PROGRESS_STEPS).coerceAtLeast(MIN_PROGRESS_BYTES)
            if (done - lastReported >= step || done >= total) {
                lastReported = done
                _state.update { it.copy(transfer = FileTransfer(label, done, total)) }
            }
        }
    }

    /** Told by the panel once it has been measured; see [FilesUiState.actionColumns]. */
    fun setActionColumns(columns: Int) {
        _state.update { it.copy(actionColumns = columns.coerceAtLeast(1)) }
    }

    fun focusAction(index: Int) {
        _state.update {
            it.copy(
                pane = FilesPane.ACTIONS,
                actionCursor = index.coerceIn(0, FileAction.entries.lastIndex),
            )
        }
    }

    private fun onActionPaneCommand(
        current: FilesUiState,
        command: ControllerCommand,
    ): FilesOutcome = when (command) {
        /*
         * Left and right walk the bar; up leaves it. Down does nothing.
         *
         * The buttons are laid out by an adaptive grid, so how many of them share
         * a row is decided at draw time by the width of the panel — which means
         * this cannot know it, and treating Up as "one back" made the pad move
         * sideways when the user pressed a vertical direction. One axis for
         * moving along the bar and the other for leaving it is a rule that holds
         * however the grid reflows.
         *
         * Up rather than Down for leaving, because the listing is the screen
         * physically above this one.
         */
        ControllerCommand.NAVIGATE_LEFT -> {
            val next = nextEnabledAction(current, current.actionCursor, -1)
            if (next == current.actionCursor) {
                FilesOutcome.REJECTED
            } else {
                focusAction(next)
                FilesOutcome.MOVED
            }
        }

        ControllerCommand.NAVIGATE_RIGHT -> {
            val next = nextEnabledAction(current, current.actionCursor, 1)
            if (next == current.actionCursor) {
                FilesOutcome.REJECTED
            } else {
                focusAction(next)
                FilesOutcome.MOVED
            }
        }

        /*
         * Up and down move a row; up out of the top row leaves for the listing,
         * which is the screen physically above this one.
         */
        ControllerCommand.NAVIGATE_UP -> {
            val next = actionBelow(current, current.actionCursor, rows = -1)
            if (next == current.actionCursor) {
                leaveActions()
                FilesOutcome.BACK
            } else {
                focusAction(next)
                FilesOutcome.MOVED
            }
        }

        ControllerCommand.NAVIGATE_DOWN -> {
            val next = actionBelow(current, current.actionCursor, rows = 1)
            if (next == current.actionCursor) {
                FilesOutcome.REJECTED
            } else {
                focusAction(next)
                FilesOutcome.MOVED
            }
        }

        ControllerCommand.CONFIRM -> {
            val action = current.focusedAction
            if (action == null || !action.isEnabled(current)) {
                FilesOutcome.REJECTED
            } else {
                performAction(action)
                FilesOutcome.ACTED
            }
        }

        ControllerCommand.BACK, ControllerCommand.CONTEXT_MENU -> {
            leaveActions()
            FilesOutcome.BACK
        }

        else -> FilesOutcome.REJECTED
    }

    fun performAction(action: FileAction) {
        val current = _state.value

        when (action) {
            FileAction.OPEN -> current.focused?.let(::openExternally)

            FileAction.MARK -> toggleMark()

            /*
             * Ticks everything here, and leaves marks made elsewhere alone.
             *
             * This compared the whole mark set against this folder's contents and
             * replaced it wholesale, so with anything ticked in another directory
             * the comparison could never match — "Select all" always took the
             * first branch and silently threw those marks away. Now that marks
             * outlive navigation, that is the set somebody had walked here to add
             * to.
             *
             * A second press unticks this folder again, so the button is a toggle
             * rather than a one-way action with no partner.
             */
            FileAction.MARK_ALL -> _state.update { state ->
                val here = state.entries.map(FileEntry::path)
                if (here.isEmpty()) return@update state

                val allTicked = state.marked.containsAll(here)
                state.copy(
                    marked = if (allTicked) {
                        state.marked - here.toSet()
                    } else {
                        LinkedHashSet(state.marked).apply { addAll(here) }
                    },
                )
            }

            FileAction.COPY -> hold(move = false)
            FileAction.CUT -> hold(move = true)
            FileAction.PASTE -> paste()

            FileAction.RENAME -> current.focused?.let { entry ->
                _state.update { it.copy(prompt = FilesPrompt.Rename(entry.path, entry.name)) }
            }

            FileAction.NEW_FOLDER -> _state.update {
                it.copy(prompt = FilesPrompt.NewFolder(""))
            }

            FileAction.DELETE -> {
                val targets = current.targets
                if (targets.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            prompt = FilesPrompt.ConfirmDelete(
                                paths = targets,
                                elsewhere = it.markedElsewhere,
                            ),
                        )
                    }
                }
            }

            FileAction.COMPRESS -> {
                val targets = current.targets
                if (targets.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            prompt = FilesPrompt.Compress(
                                paths = targets,
                                // One thing packed takes its own name; several
                                // take the folder's, which is the only name that
                                // describes all of them.
                                name = if (targets.size == 1) {
                                    fileNameOf(targets.first()).substringBeforeLast('.')
                                } else {
                                    fileNameOf(current.path).ifEmpty { "Archive" }
                                },
                            ),
                        )
                    }
                }
            }

            FileAction.EXTRACT -> current.focused?.let(::extract)

            FileAction.SORT -> cycleSort(1)
            FileAction.HIDDEN -> toggleHidden()
        }
    }

    // ---- Marking and holding -----------------------------------------------

    fun toggleMark(path: String? = null) {
        val target = path ?: _state.value.focused?.path ?: return
        _state.update { current ->
            current.copy(
                // A LinkedHashSet either way, so ticking order is the order things
                // are copied in rather than whatever a hash produced.
                marked = if (target in current.marked) {
                    current.marked - target
                } else {
                    LinkedHashSet(current.marked).apply { add(target) }
                },
            )
        }
    }

    /** Drops every mark, wherever it was made. */
    fun clearMarks() {
        _state.update { it.copy(marked = emptySet()) }
    }

    private fun hold(move: Boolean) {
        val targets = _state.value.targets
        if (targets.isEmpty()) return
        // No message: the clipboard line on the action panel already says what is
        // held and what Paste will do with it, in the accent rather than in red.
        _state.update {
            it.copy(clipboard = FileClipboard(targets, move), marked = emptySet())
        }
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboard = null, message = null) }
    }

    private fun paste() {
        val current = _state.value
        val clipboard = current.clipboard ?: return
        val destination = current.path

        transferJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    transfer = FileTransfer(clipboard.label, 0L, 0L),
                    message = null,
                )
            }

            /*
             * The bar comes down whatever happens, including a cancellation.
             *
             * `transfer` being non-null is what disables every button on the
             * panel, so a job that ended without clearing it left the explorer
             * permanently unable to do anything — indistinguishable from a copy
             * that never finished. Cancellation skips the lines after the
             * suspending call, so it has to be a `finally`.
             */
            try {
                val result = repository.transfer(
                    paths = clipboard.paths,
                    destination = destination,
                    move = clipboard.move,
                    onProgress = throttledProgress(clipboard.label),
                )

                _state.update {
                    it.copy(
                        // A move empties the clipboard; the files it named are gone
                        // from where they were, so a second paste would find
                        // nothing. A copy keeps it, because pasting the same thing
                        // into two folders is something people do on purpose.
                        clipboard = if (clipboard.move && result is FileResult.Done) {
                            null
                        } else {
                            it.clipboard
                        },
                        message = result.messageOrNull(),
                    )
                }
            } finally {
                _state.update { it.copy(transfer = null) }
                reload()
            }
        }
    }

    /** Packs the marked set, or the cursor, into a zip beside them. */
    private fun compress(prompt: FilesPrompt.Compress) {
        val destination = _state.value.path
        transferJob = viewModelScope.launch {
            _state.update { it.copy(transfer = FileTransfer("Packing", 0L, 0L), message = null) }

            try {
                val result = repository.compress(
                    paths = prompt.paths,
                    destination = destination,
                    archiveName = prompt.name,
                    onProgress = throttledProgress("Packing"),
                )
                _state.update {
                    it.copy(marked = emptySet(), message = result.messageOrNull())
                }
            } finally {
                // See the note in `paste`: the busy flag must not survive a
                // cancelled job, or every button stays dead for good.
                _state.update { it.copy(transfer = null) }
                reload()
            }
        }
    }

    /** Unpacks a zip into a folder of its own, beside it. */
    private fun extract(entry: FileEntry) {
        transferJob = viewModelScope.launch {
            _state.update { it.copy(transfer = FileTransfer("Extracting", 0L, 0L), message = null) }

            try {
                val result = repository.extract(
                    path = entry.path,
                    onProgress = throttledProgress("Extracting"),
                )
                _state.update { it.copy(message = result.messageOrNull()) }
            } finally {
                // See the note in `paste`.
                _state.update { it.copy(transfer = null) }
                reload()
            }
        }
    }

    private fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        // The bar going away is the report. A cancelled copy is not a failure.
        _state.update { it.copy(transfer = null) }
        reload()
    }

    // ---- Prompts -----------------------------------------------------------

    fun dismissPrompt() {
        _state.update { it.copy(prompt = null) }
    }

    fun setPromptText(text: String) {
        _state.update { current ->
            current.copy(
                prompt = when (val prompt = current.prompt) {
                    is FilesPrompt.Rename -> prompt.copy(name = text)
                    is FilesPrompt.NewFolder -> prompt.copy(name = text)
                    is FilesPrompt.Compress -> prompt.copy(name = text)
                    else -> prompt
                },
            )
        }
    }

    fun focusPromptConfirm(confirm: Boolean) {
        _state.update { current ->
            current.copy(
                prompt = when (val prompt = current.prompt) {
                    is FilesPrompt.Rename -> prompt.copy(confirmFocused = confirm)
                    is FilesPrompt.NewFolder -> prompt.copy(confirmFocused = confirm)
                    is FilesPrompt.Compress -> prompt.copy(confirmFocused = confirm)
                    is FilesPrompt.ConfirmDelete -> prompt.copy(confirmFocused = confirm)
                    null -> null
                },
            )
        }
    }

    private fun onPromptCommand(prompt: FilesPrompt, command: ControllerCommand): FilesOutcome =
        when (command) {
            ControllerCommand.NAVIGATE_LEFT -> {
                focusPromptConfirm(true)
                FilesOutcome.MOVED
            }

            ControllerCommand.NAVIGATE_RIGHT -> {
                focusPromptConfirm(false)
                FilesOutcome.MOVED
            }

            ControllerCommand.CONFIRM -> {
                if (prompt.confirmFocused) commitPrompt() else dismissPrompt()
                FilesOutcome.ACTED
            }

            ControllerCommand.BACK -> {
                dismissPrompt()
                FilesOutcome.BACK
            }

            else -> FilesOutcome.REJECTED
        }

    fun commitPrompt() {
        val prompt = _state.value.prompt ?: return
        dismissPrompt()

        // Packing is the one answer here that takes long enough to need a bar.
        if (prompt is FilesPrompt.Compress) {
            compress(prompt)
            return
        }

        viewModelScope.launch {
            val result = when (prompt) {
                is FilesPrompt.Rename -> repository.rename(prompt.path, prompt.name)
                is FilesPrompt.NewFolder ->
                    repository.createDirectory(_state.value.path, prompt.name)

                is FilesPrompt.ConfirmDelete -> repository.delete(prompt.paths)
                // Handled above: it needs a progress bar, so it does not belong
                // in a branch whose whole job is to await one short call.
                is FilesPrompt.Compress -> FileResult.Done
            }

            _state.update { it.copy(message = result.messageOrNull(), marked = emptySet()) }

            // The renamed file, so the cursor follows it rather than staying on
            // whatever has taken its old position in the ordering.
            val follow = when (prompt) {
                is FilesPrompt.Rename -> siblingPath(prompt.path, prompt.name)
                is FilesPrompt.NewFolder -> childPathOf(_state.value.path, prompt.name)
                is FilesPrompt.ConfirmDelete, is FilesPrompt.Compress -> null
            }
            reload(keepCursorOn = follow)
        }
    }

    // ---- Ordering ----------------------------------------------------------

    /**
     * One button cycles both the field and the direction.
     *
     * Eight states in a ring rather than a mode and a separate direction toggle,
     * which would be two controls for a thing nobody thinks of as two decisions.
     */
    private fun cycleSort(delta: Int) {
        _state.update { current ->
            val order = FileSort.entries
            val position = current.sort.ordinal * 2 + if (current.descending) 1 else 0
            val next = Math.floorMod(position + delta, order.size * 2)
            current.copy(sort = order[next / 2], descending = next % 2 == 1)
        }
        reload()
    }

    fun setSort(sort: FileSort) {
        _state.update { current ->
            current.copy(
                sort = sort,
                // Picking the field you are already on flips the direction, which is
                // what a column header does everywhere else.
                descending = if (current.sort == sort) !current.descending else false,
            )
        }
        reload()
    }

    private fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        // Re-read rather than re-filter: the hidden entries were dropped on the way
        // in, so they are not in `entries` to be shown again.
        reload()
    }

    // ---- Leaving the launcher ----------------------------------------------

    /**
     * Hands a file to whatever on the device can open it.
     *
     * Through a [FileProvider], because a `file://` URI is refused outright from
     * Android 7 onwards — `FileUriExposedException`, thrown at the sender, so the
     * failure belongs to the launcher rather than to the app it was talking to.
     */
    private fun openExternally(entry: FileEntry): FilesOutcome {
        if (entry.isDirectory) return FilesOutcome.REJECTED

        /*
         * A file on a share cannot be handed to another app.
         *
         * A `FileProvider` URI is built from a real path on this device, and there
         * is none — the bytes are on a server. Android has no general way to lend
         * another application a stream the launcher is holding open over SMB, so
         * the honest answer is to say what to do instead rather than to fail with
         * a provider error nobody can act on.
         */
        if (isRemotePath(entry.path)) {
            _state.update {
                it.copy(
                    message = "Copy ${entry.name} to this device first — " +
                        "other apps cannot read a network share",
                )
            }
            return FilesOutcome.REJECTED
        }

        val uri = runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.files",
                File(entry.path),
            )
        }.getOrElse { error ->
            ThorLog.w(TAG, "Cannot share ${entry.name}: ${error.message}")
            _state.update { it.copy(message = "Android will not share this file with other apps") }
            return FilesOutcome.REJECTED
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, entry.mimeType())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            // A chooser rather than the default handler: the launcher has no
            // business deciding that a zip belongs to one particular app forever.
            appContext.startActivity(Intent.createChooser(intent, entry.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            FilesOutcome.OPENED
        } catch (e: ActivityNotFoundException) {
            ThorLog.w(TAG, "Nothing opens ${entry.name}: ${e.message}")
            _state.update { it.copy(message = "Nothing on this device opens ${entry.extension} files") }
            FilesOutcome.REJECTED
        }
    }

    /**
     * Opens Android's All-files-access screen.
     *
     * There is no dialog for this one. `MANAGE_EXTERNAL_STORAGE` is granted only
     * from Settings, so the explorer's job is to say why it is empty and put the
     * user in front of the switch.
     */
    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Some builds do not carry the per-app screen; the list of every app is
        // worse but it is not nothing.
        runCatching { appContext.startActivity(direct) }
            .recoverCatching { appContext.startActivity(fallback) }
            .onFailure { ThorLog.w(TAG, "No all-files-access screen: ${it.message}") }
    }

    // ---- Touch -------------------------------------------------------------

    fun focusEntry(index: Int) {
        _state.update { it.copy(cursor = index.coerceIn(0, it.entries.lastIndex.coerceAtLeast(0)), pane = FilesPane.LISTING) }
    }

    fun openEntry(entry: FileEntry) {
        if (entry.isDirectory) navigateTo(entry.path) else openExternally(entry)
    }

    fun focusShortcut(index: Int) {
        _state.update {
            it.copy(
                shortcutCursor = index.coerceIn(0, it.shortcuts.lastIndex.coerceAtLeast(0)),
                pane = FilesPane.SHORTCUTS,
            )
        }
    }

    fun openShortcut(shortcut: FileShortcut) {
        navigateTo(shortcut.path)
        _state.update { it.copy(pane = FilesPane.LISTING) }
    }

    fun openCrumb(path: String) = navigateTo(path)

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val TAG = "Files"

        /** A stick held down covers a screenful at a time rather than a row. */
        const val ACCELERATED_STEP = 4

        /** How far a bumper jumps through a long directory. */
        const val PAGE_STEP = 12

        /** Positions on the progress bar worth reporting; a screen has no more. */
        const val PROGRESS_STEPS = 100

        /** ...and a floor, so a small file does not report on every chunk. */
        const val MIN_PROGRESS_BYTES = 512L * 1024L
    }
}

/** What a press did, in the terms the shell needs to answer it. */
enum class FilesOutcome {
    MOVED,
    ACTED,
    OPENED,
    BACK,

    /** The explorer is done — the shell closes the overlay. */
    CLOSE,

    REJECTED,
}

/** The sentence to show, or null when it worked and there is nothing to say. */
private fun FileResult.messageOrNull(): String? = when (this) {
    FileResult.Done -> null
    is FileResult.Invalid -> reason
    is FileResult.Failed -> reason
}

/**
 * The type to hand another app, guessed from the extension.
 *
 * The wildcard type rather than nothing when it is unknown: an empty type means
 * no app matches the intent at all, so an unrecognised file would look
 * unopenable when the device may well have something for it.
 */
private fun FileEntry.mimeType(): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: when (kind) {
            FileKind.IMAGE -> "image/*"
            FileKind.VIDEO -> "video/*"
            FileKind.AUDIO -> "audio/*"
            FileKind.DOCUMENT -> "text/*"
            FileKind.APP -> "application/vnd.android.package-archive"
            else -> "*/*"
        }
