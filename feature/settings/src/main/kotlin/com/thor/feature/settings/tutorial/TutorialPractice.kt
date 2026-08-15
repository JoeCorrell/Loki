package com.thor.feature.settings.tutorial

import com.thor.core.model.ControllerCommand

/** The small, consequence-free launcher simulation shown by an interactive lesson. */
enum class TutorialDemo {
    DUAL_SCREEN,
    NAVIGATION,
    LAUNCH_AND_BACK,
    LIBRARY_ACTIONS,
    MEDIA_BROWSING,
    SHELL_TOOLS,
    ARRANGE,
    POINTER,
    SETTINGS,
    READY,
}

/** One logical control the learner is invited to try. */
data class TutorialTask(
    val command: ControllerCommand,
    val instruction: String,
)

/** A practice lesson. Commands are logical, so custom controller mappings still work. */
data class TutorialPractice(
    val title: String,
    val tasks: List<TutorialTask>,
) {
    init {
        require(tasks.isNotEmpty()) { "A tutorial practice needs at least one task" }
        require(tasks.map { it.command }.distinct().size == tasks.size) {
            "A tutorial practice cannot require the same command twice"
        }
    }
}

/**
 * State for the simulated surface inside the walkthrough.
 *
 * It never points at real launcher state. A practice press can therefore teach
 * launching, rearranging, paging and menus without opening an app or moving a
 * user's icon underneath the modal tour.
 */
data class TutorialPracticeProgress(
    val completed: Set<ControllerCommand> = emptySet(),
    val lastCommand: ControllerCommand? = null,
    val cursorColumn: Int = 1,
    val cursorRow: Int = 1,
    val launched: Boolean = false,
    val favorite: Boolean = false,
    val contextMenuVisible: Boolean = false,
    val page: Int = 1,
    val image: Int = 1,
    val shellTool: ControllerCommand? = null,
    val carryingIcon: Boolean = false,
) {
    fun isComplete(practice: TutorialPractice?): Boolean =
        practice == null || practice.tasks.all { it.command in completed }
}

/**
 * True when [command] is the next requested control.
 *
 * Lessons are short sequences rather than bags of buttons. This prevents Back
 * before Confirm from checking off an "open, then return" lesson while leaving
 * the simulated app open, and makes pick-up/cancel teach the real order.
 */
fun TutorialStep.acceptsPracticeCommand(
    progress: TutorialPracticeProgress,
    command: ControllerCommand,
): Boolean = practice
    ?.tasks
    ?.firstOrNull { it.command !in progress.completed }
    ?.command == command

/**
 * Applies a controller or touch practice action to the tutorial simulation.
 * Unrequested commands are deliberately ignored and can never leak through to
 * the live launcher beneath the walkthrough.
 */
fun TutorialStep.reducePractice(
    progress: TutorialPracticeProgress,
    command: ControllerCommand,
): TutorialPracticeProgress {
    if (!acceptsPracticeCommand(progress, command)) return progress

    return progress.copy(
        completed = progress.completed + command,
        lastCommand = command,
        cursorColumn = when (command) {
            ControllerCommand.NAVIGATE_LEFT -> (progress.cursorColumn - 1).coerceAtLeast(0)
            ControllerCommand.NAVIGATE_RIGHT -> (progress.cursorColumn + 1).coerceAtMost(2)
            else -> progress.cursorColumn
        },
        cursorRow = when (command) {
            ControllerCommand.NAVIGATE_UP -> (progress.cursorRow - 1).coerceAtLeast(0)
            ControllerCommand.NAVIGATE_DOWN -> (progress.cursorRow + 1).coerceAtMost(2)
            else -> progress.cursorRow
        },
        launched = when (command) {
            ControllerCommand.CONFIRM -> true
            ControllerCommand.BACK -> false
            else -> progress.launched
        },
        favorite = if (command == ControllerCommand.TOGGLE_FAVORITE) {
            !progress.favorite
        } else {
            progress.favorite
        },
        contextMenuVisible = when (command) {
            ControllerCommand.CONTEXT_MENU -> true
            ControllerCommand.BACK -> false
            else -> progress.contextMenuVisible
        },
        page = when (command) {
            ControllerCommand.PAGE_PREVIOUS -> (progress.page - 1).coerceAtLeast(0)
            ControllerCommand.PAGE_NEXT -> (progress.page + 1).coerceAtMost(2)
            else -> progress.page
        },
        image = when (command) {
            ControllerCommand.CYCLE_IMAGE_PREVIOUS -> (progress.image - 1).coerceAtLeast(0)
            ControllerCommand.CYCLE_IMAGE_NEXT -> (progress.image + 1).coerceAtMost(2)
            else -> progress.image
        },
        shellTool = command.takeIf {
            it == ControllerCommand.OPEN_SIDE_MENU ||
                it == ControllerCommand.OPEN_APP_DRAWER ||
                it == ControllerCommand.OPEN_SHORTCUTS
        } ?: progress.shellTool,
        carryingIcon = when (command) {
            ControllerCommand.PICK_UP -> true
            ControllerCommand.BACK,
            ControllerCommand.CANCEL_EDIT,
            -> false
            else -> progress.carryingIcon
        },
    )
}
