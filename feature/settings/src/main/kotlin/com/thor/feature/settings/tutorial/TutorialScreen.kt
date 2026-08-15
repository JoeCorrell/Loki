@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.thor.feature.settings.tutorial

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thor.core.designsystem.component.GlassSurface
import com.thor.core.designsystem.modifier.SurfaceLevel
import com.thor.core.designsystem.theme.ThorTheme
import com.thor.core.input.controllerHint
import com.thor.core.model.ControllerCommand
import com.thor.core.model.ControllerProfile
import com.thor.core.model.PanelLayout
import com.thor.feature.settings.component.SettingsTextButton

/**
 * One display's half of Loki's modal, interactive walkthrough.
 *
 * The named display shows the coach card while both displays consume input. A
 * practice action changes only [progress], never the launcher visible beneath
 * it. That makes even launch, move and context-menu lessons safe to explore.
 */
@Composable
fun TutorialScreen(
    steps: List<TutorialStep>,
    index: Int,
    panel: TutorialPanel,
    progress: TutorialPracticeProgress,
    controllerProfile: ControllerProfile,
    exitArmed: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    onPracticeCommand: (ControllerCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val colors = ThorTheme.colors
    val animationsEnabled = ThorTheme.materials.animationsEnabled
    val isLast = index == steps.lastIndex
    val practiceComplete = progress.isComplete(step.practice)
    val backHint = controllerProfile.controllerHint(ControllerCommand.BACK)
    val confirmHint = controllerProfile.controllerHint(ControllerCommand.CONFIRM)
    val backLabel = if (backHint.isMapped) "${backHint.compactText}  BACK" else "BACK"
    val forwardLabel = when {
        isLast -> "DONE"
        step.practice != null && !practiceComplete -> "PRACTICE"
        else -> "NEXT"
    }.let { action ->
        if (practiceComplete && confirmHint.isMapped) "${confirmHint.compactText}  $action" else action
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        Spotlight(spot = if (step.panel == panel) step.spot else TutorialSpot.NONE)
        if (step.panel != panel) return@BoxWithConstraints

        val alignment = if (step.spot == TutorialSpot.NAV_BAR) {
            Alignment.TopCenter
        } else {
            Alignment.BottomCenter
        }
        val layoutMode = tutorialLayoutMode(maxWidth.value, maxHeight.value)
        val compact = layoutMode == TutorialLayoutMode.COMPACT
        val outerPadding = if (compact) 10.dp else 14.dp
        val contentPadding = if (compact) 12.dp else 16.dp
        val reservedSpotHeight = if (step.spot == TutorialSpot.NAV_BAR) {
            PanelLayout.NAV_BAR_HEIGHT.dp + outerPadding
        } else {
            0.dp
        }
        val cardMaxHeight = (
            maxHeight - reservedSpotHeight - outerPadding * 2
            ).coerceAtLeast(0.dp)
        val cardMaxWidth = when {
            compact -> maxWidth
            step.practice != null -> WIDE_PRACTICE_CARD_MAX_WIDTH_DP.dp
            else -> WIDE_READING_CARD_MAX_WIDTH_DP.dp
        }
        val copyScroll = rememberScrollState()
        val practiceScroll = rememberScrollState()
        val stackedScroll = rememberScrollState()

        // A remembered scroll offset from a long lesson otherwise opens the next
        // one halfway through its copy, hiding its title and first instructions.
        LaunchedEffect(index, layoutMode) {
            copyScroll.scrollTo(0)
            practiceScroll.scrollTo(0)
            stackedScroll.scrollTo(0)
        }

        GlassSurface(
            shape = ThorTheme.shapes.panel,
            color = colors.surface,
            level = SurfaceLevel.RAISED,
            modifier = Modifier
                .align(alignment)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                )
                .padding(outerPadding)
                // Cap before filling: the reverse order fixes the child's
                // constraints to the full panel and makes widthIn ineffective.
                .widthIn(max = cardMaxWidth)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(
                        max = cardMaxHeight,
                    )
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.chapter.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.cursor,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "INTERACTIVE WALKTHROUGH  /  ${controllerProfile.name.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "${index + 1} / ${steps.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (layoutMode == TutorialLayoutMode.WIDE && step.practice != null) {
                    /*
                     * The upper panel has enough width for two readable columns.
                     * Keeping the simulation beside the prose shortens the card
                     * and leaves the highlighted launcher surface visible.
                     */
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.44f)
                                .verticalScroll(copyScroll),
                        ) {
                            AnimatedLessonCopy(
                                steps = steps,
                                index = index,
                                animationsEnabled = animationsEnabled,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.56f)
                                .verticalScroll(practiceScroll),
                        ) {
                            PracticePanel(
                                step = step,
                                practice = step.practice,
                                progress = progress,
                                controllerProfile = controllerProfile,
                                compact = false,
                                onCommand = onPracticeCommand,
                            )
                        }
                    }
                } else {
                    /*
                     * The lower panel remains a single, bounded reading stream.
                     * Practice tasks use full-width rows here so labels survive
                     * both Loki's text scale and Android's font scale.
                     */
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(stackedScroll),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                    ) {
                        AnimatedLessonCopy(
                            steps = steps,
                            index = index,
                            animationsEnabled = animationsEnabled,
                        )
                        step.practice?.let { practice ->
                            PracticePanel(
                                step = step,
                                practice = practice,
                                progress = progress,
                                controllerProfile = controllerProfile,
                                compact = compact,
                                onCommand = onPracticeCommand,
                            )
                        } ?: DemoPreview(
                            demo = step.demo,
                            progress = progress,
                            compact = compact,
                            nextCommand = null,
                            onCommand = onPracticeCommand,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ThorTheme.shapes.small)
                        .background(colors.surfaceHighest.copy(alpha = 0.52f))
                        .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProgressRail(index = index, total = steps.size)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsTextButton(
                        label = if (exitArmed) "CONFIRM SKIP" else "SKIP",
                        containerColor = if (exitArmed) {
                            colors.error.copy(alpha = 0.12f)
                        } else {
                            Color.Transparent
                        },
                        contentColor = if (exitArmed) colors.error else null,
                        borderColor = if (exitArmed) {
                            colors.error.copy(alpha = 0.45f)
                        } else {
                            Color.Transparent
                        },
                        reactToHover = true,
                        onClick = onExit,
                    )
                    SettingsTextButton(
                        label = backLabel,
                        containerColor = Color.Transparent,
                        borderColor = Color.Transparent,
                        enabled = index > 0,
                        reactToHover = index > 0,
                        onClick = onBack.takeIf { index > 0 },
                    )
                    TutorialInputGuidance(
                        controllerProfile = controllerProfile,
                        step = step,
                        progress = progress,
                        index = index,
                        exitArmed = exitArmed,
                        modifier = Modifier.weight(1f),
                    )
                    SettingsTextButton(
                        label = forwardLabel,
                        icon = Icons.Rounded.Check.takeIf { isLast },
                        containerColor = colors.cursor.copy(alpha = 0.16f),
                        contentColor = colors.cursor,
                        borderColor = colors.cursor.copy(alpha = 0.5f),
                        focused = practiceComplete,
                        enabled = practiceComplete,
                        reactToHover = practiceComplete,
                        onClick = onNext.takeIf { practiceComplete },
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun TutorialInputGuidance(
    controllerProfile: ControllerProfile,
    step: TutorialStep,
    progress: TutorialPracticeProgress,
    index: Int,
    exitArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val nextTask = step.practice
        ?.tasks
        ?.firstOrNull { it.command !in progress.completed }
    val practiceComplete = progress.isComplete(step.practice)
    val backIsTask = nextTask?.command == ControllerCommand.BACK
    val confirmIsTask = nextTask?.command == ControllerCommand.CONFIRM
    val guidance = when {
        nextTask != null && !controllerProfile.controllerHint(nextTask.command).isMapped ->
            "Not mapped in this profile. Tap the highlighted Home preview or task to try it safely."
        exitArmed && index > 0 ->
            "Tap Confirm Skip again to leave; controller Back returns to the previous lesson."
        nextTask != null ->
            "Use the highlighted control, or tap the fake Home. Your real library stays locked."
        backIsTask -> "Back completes this practice step."
        confirmIsTask -> "Confirm completes this practice step."
        else -> "Controller labels follow your active profile; touch works too."
    }
    Text(
        text = guidance,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onSurfaceVariant.copy(alpha = 0.72f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AnimatedLessonCopy(
    steps: List<TutorialStep>,
    index: Int,
    animationsEnabled: Boolean,
) {
    val colors = ThorTheme.colors
    val dimens = ThorTheme.dimens
    val motion = ThorTheme.motion

    AnimatedContent(
        targetState = index,
        transitionSpec = {
            if (animationsEnabled) {
                (slideInHorizontally(
                    animationSpec = motion.tweenSpec(motion.detailMillis),
                    initialOffsetX = { it / 5 },
                ) + fadeIn(motion.tweenSpec(motion.detailMillis))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = motion.tweenSpec(motion.detailMillis),
                        targetOffsetX = { -it / 5 },
                    ) + fadeOut(motion.tweenSpec(motion.detailMillis)))
            } else {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            }
        },
        label = "tutorial-lesson",
    ) { current ->
        val shown = steps[current.coerceIn(0, steps.lastIndex)]
        Column(
            verticalArrangement = Arrangement.spacedBy(dimens.spacingSmall),
        ) {
            Text(
                text = shown.title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = shown.body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            shown.detailPoints.forEach { point -> DetailPoint(point) }
            shown.hint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.cursor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DetailPoint(text: String) {
    val colors = ThorTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.cursor),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ControllerKeyCap(
    label: String,
    mapped: Boolean,
    emphasized: Boolean,
    muted: Boolean = false,
) {
    val colors = ThorTheme.colors
    val accent = if (mapped) colors.cursor else colors.error
    Row(
        modifier = Modifier
            .clip(ThorTheme.shapes.pill)
            .background(
                accent.copy(
                    alpha = when {
                        emphasized -> 0.20f
                        muted -> 0.04f
                        else -> 0.10f
                    },
                ),
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Gamepad,
            contentDescription = null,
            tint = accent.copy(alpha = if (muted) 0.55f else 0.9f),
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent.copy(alpha = if (muted) 0.55f else 1f),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PracticePanel(
    step: TutorialStep,
    practice: TutorialPractice,
    progress: TutorialPracticeProgress,
    controllerProfile: ControllerProfile,
    compact: Boolean,
    onCommand: (ControllerCommand) -> Unit,
) {
    val colors = ThorTheme.colors
    val motion = ThorTheme.motion
    val complete = progress.isComplete(practice)
    val completedCount = practice.tasks.count { it.command in progress.completed }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ThorTheme.shapes.small)
            .background(colors.cursor.copy(alpha = if (complete) 0.13f else 0.07f))
            .border(
                width = if (complete) 2.dp else 1.dp,
                color = colors.cursor.copy(alpha = if (complete) 0.58f else 0.25f),
                shape = ThorTheme.shapes.small,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "TRY IT NOW",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.cursor,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = practice.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "$completedCount / ${practice.tasks.size}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
        }

        val nextCommand = practice.tasks
            .firstOrNull { it.command !in progress.completed }
            ?.command
        DemoPreview(
            demo = step.demo,
            progress = progress,
            compact = compact,
            nextCommand = nextCommand,
            onCommand = onCommand,
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = if (compact) 1 else 2,
        ) {
            practice.tasks.forEach { task ->
                val done = task.command in progress.completed
                val available = !done && task.command == nextCommand
                val controlHint = controllerProfile.controllerHint(task.command)
                val taskBackground by animateColorAsState(
                    targetValue = when {
                        done -> colors.cursor.copy(alpha = 0.18f)
                        available -> colors.cursor.copy(alpha = 0.08f)
                        else -> colors.surfaceHighest.copy(alpha = 0.62f)
                    },
                    animationSpec = motion.tweenSpec(motion.selectionMillis),
                    label = "tutorial-task-background",
                )
                val taskBorder by animateColorAsState(
                    targetValue = when {
                        done -> colors.cursor.copy(alpha = 0.54f)
                        available -> colors.cursor.copy(alpha = 0.38f)
                        else -> colors.outline.copy(alpha = 0.18f)
                    },
                    animationSpec = motion.tweenSpec(motion.selectionMillis),
                    label = "tutorial-task-border",
                )
                Row(
                    modifier = Modifier
                        .then(
                            if (compact) Modifier.fillMaxWidth()
                            else Modifier.weight(1f),
                        )
                        .clip(ThorTheme.shapes.pill)
                        .background(taskBackground)
                        .border(
                            width = 1.dp,
                            color = taskBorder,
                            shape = ThorTheme.shapes.pill,
                        )
                        .clickable(
                            enabled = available,
                            onClickLabel = task.instruction,
                        ) {
                            onCommand(task.command)
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (done) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = colors.cursor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = task.instruction,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            done -> colors.onSurface
                            available -> colors.onSurfaceVariant
                            else -> colors.onSurfaceVariant.copy(alpha = 0.48f)
                        },
                        fontWeight = if (done || available) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ControllerKeyCap(
                        label = controlHint.compactText,
                        mapped = controlHint.isMapped,
                        emphasized = available,
                        muted = !available && !done,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = complete,
            enter = fadeIn(motion.tweenSpec(motion.selectionMillis)) + scaleIn(
                animationSpec = motion.tweenSpec(motion.selectionMillis),
                initialScale = 0.94f,
            ),
            exit = fadeOut(motion.tweenSpec(motion.selectionMillis)) + scaleOut(
                animationSpec = motion.tweenSpec(motion.selectionMillis),
                targetScale = 0.94f,
            ),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Practice complete — Confirm continues",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.cursor,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * A small but real stateful Home screen. Every practice command is rendered here
 * before it is marked complete, while the actual launcher remains completely
 * untouched beneath the modal walkthrough.
 */
@Composable
private fun DemoPreview(
    demo: TutorialDemo,
    progress: TutorialPracticeProgress,
    compact: Boolean,
    nextCommand: ControllerCommand?,
    onCommand: (ControllerCommand) -> Unit,
) {
    val colors = ThorTheme.colors
    val pageItems = TUTORIAL_LIBRARY_PAGES[progress.page.coerceIn(0, 2)]
    val selectedIndex = (progress.cursorRow * 3 + progress.cursorColumn)
        .coerceIn(0, pageItems.lastIndex)
    val selected = pageItems[selectedIndex]
    val interaction = nextCommand?.let { command ->
        Modifier.clickable(
            onClickLabel = "Try ${command.label} on the fake Home",
        ) { onCommand(command) }
    } ?: Modifier

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) FAKE_HOME_COMPACT_HEIGHT.dp else FAKE_HOME_HEIGHT.dp)
            .clip(ThorTheme.shapes.small)
            .background(colors.background.copy(alpha = 0.92f))
            .border(1.dp, colors.outline.copy(alpha = 0.28f), ThorTheme.shapes.small)
            .then(interaction),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.9f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.GridView,
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "  HOME  ·  PAGE ${progress.page + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (nextCommand == null) "SAFE PREVIEW" else "TAP TO TRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.cursor,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(7.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FakeLibraryGrid(
                    items = pageItems,
                    selectedIndex = selectedIndex,
                    favoriteSelected = progress.favorite,
                    carryingSelected = progress.carryingIcon,
                    // Match Loki's real grid: the grid itself is square, which
                    // makes every one of its three-by-three cells square too.
                    // Height-first avoids a wide walkthrough card stretching
                    // the fake tiles into short landscape rectangles.
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f, matchHeightConstraintsFirst = true),
                )
                FakeInfoPanel(
                    item = selected,
                    image = progress.image,
                    favorite = progress.favorite,
                    demo = demo,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (progress.launched && demo == TutorialDemo.LAUNCH_AND_BACK) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim.copy(alpha = 0.94f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.RocketLaunch,
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(30.dp),
                )
                Text(selected.title, color = colors.onSurface, fontWeight = FontWeight.Bold)
                Text(
                    "SIMULATED LAUNCH · BACK RETURNS HOME",
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (progress.contextMenuVisible && demo == TutorialDemo.LIBRARY_ACTIONS) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(10.dp)
                    .widthIn(min = 118.dp)
                    .clip(ThorTheme.shapes.small)
                    .background(colors.surfaceHighest)
                    .border(1.dp, colors.cursor.copy(alpha = 0.5f), ThorTheme.shapes.small)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    selected.title,
                    color = colors.onSurface,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                listOf("Change artwork", "Move to folder", "Game settings").forEachIndexed { i, action ->
                    Text(
                        text = action,
                        color = if (i == 0) colors.cursor else colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (i == 0) colors.cursor.copy(alpha = 0.12f) else Color.Transparent)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                }
            }
        }

        if (demo == TutorialDemo.SHELL_TOOLS && progress.shellTool != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(colors.surfaceHighest.copy(alpha = 0.97f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = when (progress.shellTool) {
                        ControllerCommand.OPEN_APP_DRAWER -> Icons.Rounded.GridView
                        ControllerCommand.OPEN_SHORTCUTS -> Icons.Rounded.Settings
                        else -> Icons.Rounded.Folder
                    },
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "  ${progress.shellTool.label.uppercase()} · SIMULATED LAYER",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        when (demo) {
            TutorialDemo.DUAL_SCREEN ->
                FakeStatusBadge("GRID + ARTWORK STAY IN SYNC", Modifier.align(Alignment.BottomCenter))
            TutorialDemo.POINTER -> {
                Icon(
                    Icons.Rounded.Mouse,
                    contentDescription = "Simulated pointer",
                    tint = colors.cursor,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                )
                FakeStatusBadge("START + SELECT · POINTER", Modifier.align(Alignment.BottomCenter))
            }
            TutorialDemo.SETTINGS ->
                FakeStatusBadge("SETTINGS OPEN ON INFO DISPLAY", Modifier.align(Alignment.BottomCenter))
            TutorialDemo.READY ->
                FakeStatusBadge("YOUR HOME IS READY", Modifier.align(Alignment.BottomCenter))
            else -> Unit
        }
    }
}

@Composable
private fun FakeLibraryGrid(
    items: List<TutorialLibraryItem>,
    selectedIndex: Int,
    favoriteSelected: Boolean,
    carryingSelected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val lift by animateDpAsState(
        targetValue = if (carryingSelected) 5.dp else 0.dp,
        animationSpec = ThorTheme.motion.dragSpring(),
        label = "tutorial-fake-card-lift",
    )
    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { row ->
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(3) { column ->
                    val itemIndex = row * 3 + column
                    val item = items[itemIndex]
                    val selected = itemIndex == selectedIndex
                    val tint = tutorialArtworkColor(itemIndex)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(bottom = if (selected && carryingSelected) lift else 0.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(tint.copy(alpha = 0.74f), colors.surfaceElevated),
                                ),
                            )
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) colors.cursor else colors.outline.copy(alpha = 0.32f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (item.folder) Icons.Rounded.Folder else Icons.Rounded.Gamepad,
                                contentDescription = null,
                                tint = if (item.folder) colors.onSurface else Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            if (selected && favoriteSelected) {
                                Icon(Icons.Rounded.Star, null, tint = colors.cursor, modifier = Modifier.size(11.dp))
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FakeInfoPanel(
    item: TutorialLibraryItem,
    image: Int,
    favorite: Boolean,
    demo: TutorialDemo,
    modifier: Modifier = Modifier,
) {
    val colors = ThorTheme.colors
    val imageIndex = image.coerceIn(0, 2)
    val base = tutorialArtworkColor(item.artwork + imageIndex)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.outline.copy(alpha = 0.28f), RoundedCornerShape(7.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(5.dp))
                .background(Brush.linearGradient(listOf(base, colors.secondary, colors.surfaceHighest))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (item.folder) Icons.Rounded.Folder else Icons.Rounded.RocketLaunch,
                contentDescription = "Simulated artwork for ${item.title}",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(32.dp),
            )
            if (favorite) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = "Favorited",
                    tint = colors.cursor,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(14.dp),
                )
            }
        }
        Text(
            text = item.title,
            color = colors.onSurface,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (item.folder) "PLATFORM FOLDER · ${item.subtitle}" else item.subtitle,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (demo == TutorialDemo.MEDIA_BROWSING) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { dot ->
                    Box(
                        Modifier
                            .size(width = if (dot == imageIndex) 13.dp else 5.dp, height = 4.dp)
                            .clip(ThorTheme.shapes.pill)
                            .background(if (dot == imageIndex) colors.cursor else colors.outline),
                    )
                }
            }
        }
    }
}

@Composable
private fun tutorialArtworkColor(index: Int): Color {
    val colors = ThorTheme.colors
    return when (index.mod(6)) {
        0 -> colors.primary
        1 -> colors.secondary
        2 -> colors.accentEnd
        3 -> colors.cursor
        4 -> colors.error
        else -> Color(
            red = colors.secondary.red,
            green = colors.primary.green,
            blue = colors.accentEnd.blue,
        )
    }
}

@Composable
private fun FakeStatusBadge(label: String, modifier: Modifier = Modifier) {
    val colors = ThorTheme.colors
    Text(
        text = label,
        modifier = modifier
            .padding(7.dp)
            .clip(ThorTheme.shapes.pill)
            .background(colors.surfaceHighest.copy(alpha = 0.96f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        color = colors.cursor,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

private data class TutorialLibraryItem(
    val title: String,
    val subtitle: String,
    val folder: Boolean = false,
    val artwork: Int = 0,
)

private val TUTORIAL_LIBRARY_PAGES = listOf(
    listOf(
        TutorialLibraryItem("SNES Classics", "18 games", folder = true, artwork = 0),
        TutorialLibraryItem("Hollow Knight", "Nintendo Switch", artwork = 1),
        TutorialLibraryItem("Celeste", "Nintendo Switch", artwork = 2),
        TutorialLibraryItem("PlayStation 2", "24 games", folder = true, artwork = 3),
        TutorialLibraryItem("Metroid Prime", "Nintendo GameCube", artwork = 4),
        TutorialLibraryItem("Stardew Valley", "Android", artwork = 5),
        TutorialLibraryItem("Arcade", "42 games", folder = true, artwork = 6),
        TutorialLibraryItem("Dead Cells", "Android", artwork = 7),
        TutorialLibraryItem("Moonlight", "PC streaming", artwork = 8),
    ),
    listOf(
        TutorialLibraryItem("PlayStation", "31 games", folder = true, artwork = 3),
        TutorialLibraryItem("Shadow of the Colossus", "PlayStation 2", artwork = 4),
        TutorialLibraryItem("Chrono Trigger", "SNES", artwork = 5),
        TutorialLibraryItem("Handhelds", "56 games", folder = true, artwork = 2),
        TutorialLibraryItem("Advance Wars", "Game Boy Advance", artwork = 1),
        TutorialLibraryItem("A Short Hike", "Nintendo Switch", artwork = 0),
        TutorialLibraryItem("Favorites", "12 games", folder = true, artwork = 7),
        TutorialLibraryItem("Tunic", "PC", artwork = 8),
        TutorialLibraryItem("Steam", "PC library", artwork = 6),
    ),
    listOf(
        TutorialLibraryItem("Recently Added", "9 games", folder = true, artwork = 5),
        TutorialLibraryItem("Sonic Mania", "Nintendo Switch", artwork = 3),
        TutorialLibraryItem("Castlevania", "PlayStation", artwork = 8),
        TutorialLibraryItem("Co-op Night", "Smart folder", folder = true, artwork = 4),
        TutorialLibraryItem("Shovel Knight", "Nintendo 3DS", artwork = 2),
        TutorialLibraryItem("Hades", "Nintendo Switch", artwork = 7),
        TutorialLibraryItem("Racing", "15 games", folder = true, artwork = 0),
        TutorialLibraryItem("F-Zero GX", "Nintendo GameCube", artwork = 6),
        TutorialLibraryItem("Settings", "Loki", artwork = 1),
    ),
)

/** Old icon-only sample kept as a tiny rendering fallback for previews without interaction. */
@Composable
private fun LegacyDemoPreview(
    demo: TutorialDemo,
    progress: TutorialPracticeProgress,
    compact: Boolean,
) {
    val colors = ThorTheme.colors
    val lift by animateDpAsState(
        targetValue = if (progress.carryingIcon) 7.dp else 0.dp,
        animationSpec = ThorTheme.motion.dragSpring(),
        label = "tutorial-sample-lift",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // This is intentionally a minimum, not a fixed height: wrapped copy
            // at large text sizes must make the sample taller instead of clipping.
            .heightIn(min = if (compact) COMPACT_DEMO_MIN_HEIGHT_DP.dp else DEMO_MIN_HEIGHT_DP.dp)
            .clip(ThorTheme.shapes.small)
            .background(colors.surface.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (demo) {
            TutorialDemo.NAVIGATION -> {
                MiniGrid(progress)
                Text(
                    text = "Sample cursor\nrow ${progress.cursorRow + 1}, column ${progress.cursorColumn + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }

            TutorialDemo.LAUNCH_AND_BACK -> {
                Icon(
                    imageVector = Icons.Rounded.Gamepad,
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = if (progress.launched) {
                        "Sample opened\nPress your mapped Back control"
                    } else {
                        "Sample card is Home\nPress your mapped Confirm control"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }

            TutorialDemo.LIBRARY_ACTIONS -> {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = if (progress.favorite) colors.cursor else colors.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = null,
                    tint = if (progress.contextMenuVisible) colors.cursor else colors.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = if (progress.contextMenuVisible) "Sample action menu open" else "Sample library card",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }

            TutorialDemo.MEDIA_BROWSING -> {
                Icon(
                    imageVector = Icons.Rounded.Gamepad,
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = "Home page ${progress.page + 1} of 3\nArtwork ${progress.image + 1} of 3",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }

            TutorialDemo.SHELL_TOOLS -> {
                Icon(
                    imageVector = when (progress.shellTool) {
                        ControllerCommand.OPEN_APP_DRAWER -> Icons.Rounded.Folder
                        ControllerCommand.OPEN_SHORTCUTS -> Icons.Rounded.Settings
                        else -> Icons.Rounded.Gamepad
                    },
                    contentDescription = null,
                    tint = colors.cursor,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    text = progress.shellTool?.label ?: "Home — no utility layer open",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }

            TutorialDemo.ARRANGE -> {
                Box(
                    modifier = Modifier
                        .padding(bottom = lift)
                        .size(34.dp)
                        .clip(ThorTheme.shapes.small)
                        .background(colors.cursor.copy(alpha = 0.22f))
                        .border(2.dp, colors.cursor, ThorTheme.shapes.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Gamepad,
                        contentDescription = null,
                        tint = colors.cursor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = if (progress.carryingIcon) "Sample icon picked up" else "Sample icon in its original cell",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface,
                )
            }

            TutorialDemo.POINTER -> {
                Icon(Icons.Rounded.Mouse, null, tint = colors.cursor, modifier = Modifier.size(28.dp))
                Text("Pointer and cross-app keyboard", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            }

            TutorialDemo.SETTINGS -> {
                Icon(Icons.Rounded.Settings, null, tint = colors.cursor, modifier = Modifier.size(28.dp))
                Text("The real Settings category is open on the other display", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            }

            TutorialDemo.DUAL_SCREEN,
            TutorialDemo.READY,
            -> {
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 28.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .border(2.dp, colors.cursor, RoundedCornerShape(5.dp)),
                )
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 28.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.cursor.copy(alpha = 0.18f))
                        .border(2.dp, colors.cursor, RoundedCornerShape(5.dp)),
                )
                Text("Two displays, one launcher state", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            }
        }
    }
}

@Composable
private fun MiniGrid(progress: TutorialPracticeProgress) {
    val colors = ThorTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { column ->
                    val selected = row == progress.cursorRow && column == progress.cursorColumn
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (selected) colors.cursor
                                else colors.surfaceHighest,
                            )
                            .border(
                                1.dp,
                                if (selected) colors.cursor else colors.outline.copy(alpha = 0.32f),
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Spotlight(spot: TutorialSpot) {
    if (spot == TutorialSpot.NONE) return
    val colors = ThorTheme.colors
    val pulse = if (ThorTheme.materials.animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "tutorial-spotlight")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = ThorTheme.motion.scaledDuration(PULSE_MS)),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tutorial-spotlight-pulse",
        ).value
    } else {
        0.5f
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val barTop = (size.height - PanelLayout.NAV_BAR_HEIGHT.dp.toPx()).coerceAtLeast(0f)
        val lit = when (spot) {
            TutorialSpot.GRID -> Rect(Offset.Zero, Size(size.width, barTop))
            TutorialSpot.NAV_BAR -> Rect(Offset(0f, barTop), Size(size.width, size.height - barTop))
            TutorialSpot.PANEL -> Rect(Offset.Zero, size)
            TutorialSpot.NONE -> return@Canvas
        }

        val shade = colors.scrim.copy(alpha = DIM_ALPHA)
        if (lit.top > 0f) drawRect(shade, Offset.Zero, Size(size.width, lit.top))
        if (lit.bottom < size.height) {
            drawRect(shade, Offset(0f, lit.bottom), Size(size.width, size.height - lit.bottom))
        }
        if (lit.left > 0f) drawRect(shade, Offset(0f, lit.top), Size(lit.left, lit.height))
        if (lit.right < size.width) {
            drawRect(shade, Offset(lit.right, lit.top), Size(size.width - lit.right, lit.height))
        }

        val inset = RING_INSET_DP.dp.toPx()
        val glow = RING_ALPHA_LOW + (RING_ALPHA_HIGH - RING_ALPHA_LOW) * pulse
        drawRoundRect(
            color = colors.cursor.copy(alpha = glow),
            topLeft = Offset(lit.left + inset, lit.top + inset),
            size = Size(
                (lit.width - inset * 2).coerceAtLeast(0f),
                (lit.height - inset * 2).coerceAtLeast(0f),
            ),
            cornerRadius = CornerRadius(RING_RADIUS_DP.dp.toPx()),
            style = Stroke(width = RING_STROKE_DP.dp.toPx()),
        )
    }
}

@Composable
private fun ProgressRail(index: Int, total: Int) {
    val colors = ThorTheme.colors
    val fraction = ((index + 1).toFloat() / total).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(RAIL_HEIGHT.dp)
            .clip(ThorTheme.shapes.pill)
            .background(colors.surfaceHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(RAIL_HEIGHT.dp)
                .clip(ThorTheme.shapes.pill)
                .background(colors.cursor),
        )
    }
}

private const val COMPACT_DEMO_MIN_HEIGHT_DP = 48
private const val DEMO_MIN_HEIGHT_DP = 58
private const val FAKE_HOME_COMPACT_HEIGHT = 150
private const val FAKE_HOME_HEIGHT = 176
private const val WIDE_READING_CARD_MAX_WIDTH_DP = 720
private const val WIDE_PRACTICE_CARD_MAX_WIDTH_DP = 960
private const val DIM_ALPHA = 0.78f
private const val RING_INSET_DP = 3
private const val RING_STROKE_DP = 2
private const val RING_RADIUS_DP = 10
private const val RING_ALPHA_LOW = 0.4f
private const val RING_ALPHA_HIGH = 1f
private const val PULSE_MS = 1_100
private const val RAIL_HEIGHT = 4
