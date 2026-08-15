package com.thor.feature.settings.tutorial

import com.google.common.truth.Truth.assertThat
import com.thor.core.model.ControllerCommand
import org.junit.Test

class TutorialPracticeTest {

    private val navigation = TutorialStep(
        title = "Navigation",
        body = "Practice",
        demo = TutorialDemo.NAVIGATION,
        practice = TutorialPractice(
            title = "Move",
            tasks = listOf(
                TutorialTask(ControllerCommand.NAVIGATE_UP, "Up"),
                TutorialTask(ControllerCommand.NAVIGATE_DOWN, "Down"),
                TutorialTask(ControllerCommand.NAVIGATE_LEFT, "Left"),
                TutorialTask(ControllerCommand.NAVIGATE_RIGHT, "Right"),
            ),
        ),
    )

    @Test
    fun `practice ignores commands the lesson did not request`() {
        val before = TutorialPracticeProgress()

        val after = navigation.reducePractice(before, ControllerCommand.CONFIRM)

        assertThat(after).isEqualTo(before)
        assertThat(navigation.acceptsPracticeCommand(before, ControllerCommand.CONFIRM)).isFalse()
    }

    @Test
    fun `practice completes only after every requested command`() {
        var progress = TutorialPracticeProgress()

        for (command in listOf(
            ControllerCommand.NAVIGATE_UP,
            ControllerCommand.NAVIGATE_DOWN,
            ControllerCommand.NAVIGATE_LEFT,
        )) {
            progress = navigation.reducePractice(progress, command)
        }
        assertThat(progress.isComplete(navigation.practice)).isFalse()

        progress = navigation.reducePractice(progress, ControllerCommand.NAVIGATE_RIGHT)
        assertThat(progress.isComplete(navigation.practice)).isTrue()
    }

    @Test
    fun `sample cursor remains inside its simulated grid`() {
        fun oneCommand(command: ControllerCommand) = TutorialStep(
            title = "Bounds",
            body = "Bounds",
            practice = TutorialPractice(
                title = "Bounds",
                tasks = listOf(TutorialTask(command, command.label)),
            ),
        )

        val upperLeft = TutorialPracticeProgress(cursorColumn = 0, cursorRow = 0)
        val afterUp = oneCommand(ControllerCommand.NAVIGATE_UP)
            .reducePractice(upperLeft, ControllerCommand.NAVIGATE_UP)
        val afterLeft = oneCommand(ControllerCommand.NAVIGATE_LEFT)
            .reducePractice(upperLeft, ControllerCommand.NAVIGATE_LEFT)
        assertThat(afterUp.cursorRow).isEqualTo(0)
        assertThat(afterLeft.cursorColumn).isEqualTo(0)

        val lowerRight = TutorialPracticeProgress(cursorColumn = 2, cursorRow = 2)
        val afterDown = oneCommand(ControllerCommand.NAVIGATE_DOWN)
            .reducePractice(lowerRight, ControllerCommand.NAVIGATE_DOWN)
        val afterRight = oneCommand(ControllerCommand.NAVIGATE_RIGHT)
            .reducePractice(lowerRight, ControllerCommand.NAVIGATE_RIGHT)
        assertThat(afterDown.cursorRow).isEqualTo(2)
        assertThat(afterRight.cursorColumn).isEqualTo(2)
    }

    @Test
    fun `launch and back are simulated without retaining an open sample`() {
        val step = ThorTutorial.base(emptySet()).first { it.demo == TutorialDemo.LAUNCH_AND_BACK }

        val launched = step.reducePractice(TutorialPracticeProgress(), ControllerCommand.CONFIRM)
        val returned = step.reducePractice(launched, ControllerCommand.BACK)

        assertThat(launched.launched).isTrue()
        assertThat(returned.launched).isFalse()
        assertThat(returned.isComplete(step.practice)).isTrue()
    }

    @Test
    fun `practice rejects later tasks until earlier tasks are complete`() {
        val step = ThorTutorial.base(emptySet()).first { it.demo == TutorialDemo.LAUNCH_AND_BACK }
        val initial = TutorialPracticeProgress()

        val prematureBack = step.reducePractice(initial, ControllerCommand.BACK)
        val launched = step.reducePractice(prematureBack, ControllerCommand.CONFIRM)

        assertThat(prematureBack).isEqualTo(initial)
        assertThat(launched.launched).isTrue()
        assertThat(launched.isComplete(step.practice)).isFalse()
    }

    @Test
    fun `arrange practice uses the shipped Back action to cancel`() {
        val step = ThorTutorial.base(emptySet()).first { it.demo == TutorialDemo.ARRANGE }
        val commands = step.practice!!.tasks.map { it.command }

        assertThat(commands).containsExactly(
            ControllerCommand.PICK_UP,
            ControllerCommand.BACK,
        ).inOrder()

        val pickedUp = step.reducePractice(TutorialPracticeProgress(), ControllerCommand.PICK_UP)
        val cancelled = step.reducePractice(pickedUp, ControllerCommand.BACK)
        assertThat(cancelled.carryingIcon).isFalse()
        assertThat(cancelled.isComplete(step.practice)).isTrue()
    }

    @Test
    fun `full tour contains several interactive lessons with unique tasks`() {
        val practices = ThorTutorial.base(emptySet()).mapNotNull { it.practice }

        assertThat(practices.size).isAtLeast(6)
        for (practice in practices) {
            assertThat(practice.tasks).isNotEmpty()
            assertThat(practice.tasks.map { it.command }.distinct())
                .hasSize(practice.tasks.size)
        }
    }
}
