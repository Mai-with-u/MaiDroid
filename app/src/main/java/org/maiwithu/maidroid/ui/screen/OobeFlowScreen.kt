package org.maiwithu.maidroid.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.annotation.DrawableRes
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.oobe.OobeSetupState
import org.maiwithu.maidroid.oobe.OobeTaskKind
import org.maiwithu.maidroid.oobe.OobeTask
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.oobe.AdaptiveOobeTaskList
import org.maiwithu.maidroid.ui.oobe.CompactOobeTaskList
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.ui.oobe.CommandLogButton
import org.maiwithu.maidroid.ui.oobe.OobeStepFooter
import org.maiwithu.maidroid.ui.oobe.OobeStepHeader
import org.maiwithu.maidroid.ui.oobe.OobeStepIndicator
import org.maiwithu.maidroid.ui.oobe.hasFailedTask
import org.maiwithu.maidroid.ui.oobe.oobeStepTransition
import org.maiwithu.maidroid.ui.oobe.tasksFor
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.oobe.AdaptiveOobePermissionCards
import org.maiwithu.maidroid.ui.oobe.CompactOobePermissionCards
import org.maiwithu.maidroid.ui.theme.BackgroundDark
import org.maiwithu.maidroid.ui.theme.Orange400
import org.maiwithu.maidroid.ui.theme.Orange500
import org.maiwithu.maidroid.ui.theme.SurfaceDark
import org.maiwithu.maidroid.ui.theme.TextOnOrange
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSecondary

private const val SETUP_ATTENTION_DELAY_MS = 5 * 60 * 1000L
internal const val OOBE_HERO_PANE_WEIGHT = 1f
internal const val OOBE_WORK_PANE_WEIGHT = 2f
internal const val OOBE_WORK_CONTENT_WEIGHT = 2f
internal const val OOBE_TERMINAL_PANE_WEIGHT = 1f

internal fun oobeUsesAdaptiveLayout(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600f || widthDp > heightDp

internal fun oobeHeroWidthDp(availableWidthDp: Float): Float =
    availableWidthDp.coerceAtLeast(0f) / 3f

internal fun oobeUsesEmbeddedTerminal(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp && minOf(widthDp, heightDp) >= 600f

internal fun oobeShowsEmbeddedTerminal(
    embeddedMode: Boolean,
    currentStep: OobeStep,
    terminalVisible: Boolean
): Boolean = embeddedMode && currentStep != OobeStep.Permissions && terminalVisible

internal data class OobeTerminalPresentationState(
    val visible: Boolean,
    val embeddedDefaultApplied: Boolean
)

internal fun oobeTerminalStateForEnvironment(
    currentStep: OobeStep,
    embeddedMode: Boolean,
    visible: Boolean,
    embeddedDefaultApplied: Boolean
): OobeTerminalPresentationState = when {
    currentStep == OobeStep.Permissions -> OobeTerminalPresentationState(
        visible = false,
        embeddedDefaultApplied = false
    )

    embeddedMode && !embeddedDefaultApplied -> OobeTerminalPresentationState(
        visible = true,
        embeddedDefaultApplied = true
    )

    else -> OobeTerminalPresentationState(
        visible = visible,
        embeddedDefaultApplied = embeddedDefaultApplied
    )
}

@Composable
internal fun OobeFlowScreen(
    currentStep: OobeStep,
    setupState: OobeSetupState = OobeSetupState.beforeFirstCheck(),
    permissionState: PermissionState = PermissionState(),
    permissionActions: PermissionActions = PermissionActions(),
    onNext: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundDark)
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val statusBarTop = with(density) {
            WindowInsets.statusBars.getTop(this).toDp()
        }
        val safeDrawingLeft = with(density) {
            WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp()
        }
        val safeDrawingRight = with(density) {
            WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp()
        }
        val safeDrawingBottom = with(density) {
            WindowInsets.safeDrawing.getBottom(this).toDp()
        }
        val useAdaptiveLayout = oobeUsesAdaptiveLayout(maxWidth.value, maxHeight.value)
        val useEmbeddedTerminal = oobeUsesEmbeddedTerminal(maxWidth.value, maxHeight.value)
        val headerHeight = maxHeight * 0.33f
        val contentCornerRadius = 48.dp
        val contentOverlap = contentCornerRadius
        val contentTop = maxOf(headerHeight - contentOverlap, 0.dp)
        val setupFailed = setupState.tasksFor(currentStep).hasFailedTask()
        val stepDone = when (currentStep) {
            OobeStep.Permissions -> true
            OobeStep.Container -> setupState.canInstall
            OobeStep.Install -> setupState.isComplete
        }
        var waitedTooLong by remember { mutableStateOf(false) }
        var terminalVisible by rememberSaveable { mutableStateOf(false) }
        var embeddedTerminalDefaultApplied by rememberSaveable { mutableStateOf(false) }
        val showEmbeddedTerminal = oobeShowsEmbeddedTerminal(
            embeddedMode = useEmbeddedTerminal,
            currentStep = currentStep,
            terminalVisible = terminalVisible
        )

        LaunchedEffect(currentStep, stepDone, setupFailed) {
            waitedTooLong = false
            if (currentStep != OobeStep.Permissions && !stepDone && !setupFailed) {
                delay(SETUP_ATTENTION_DELAY_MS)
                waitedTooLong = true
            }
        }

        LaunchedEffect(currentStep, useEmbeddedTerminal) {
            val resolvedState = oobeTerminalStateForEnvironment(
                currentStep = currentStep,
                embeddedMode = useEmbeddedTerminal,
                visible = terminalVisible,
                embeddedDefaultApplied = embeddedTerminalDefaultApplied
            )
            terminalVisible = resolvedState.visible
            embeddedTerminalDefaultApplied = resolvedState.embeddedDefaultApplied
        }

        if (useAdaptiveLayout) {
            AdaptiveOobeFlowLayout(
                currentStep = currentStep,
                setupState = setupState,
                permissionState = permissionState,
                permissionActions = permissionActions,
                onNext = onNext,
                onRetry = onRetry,
                commandLogAttention = setupFailed || waitedTooLong,
                embeddedTerminalMode = useEmbeddedTerminal,
                terminalExpanded = terminalVisible,
                showEmbeddedTerminal = showEmbeddedTerminal,
                onTerminalToggle = {
                    terminalVisible = !terminalVisible
                    embeddedTerminalDefaultApplied = true
                },
                statusBarTop = statusBarTop,
                safeDrawingLeft = safeDrawingLeft,
                safeDrawingRight = safeDrawingRight,
                safeDrawingBottom = safeDrawingBottom,
                availableWidth = maxWidth
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(headerHeight)
            ) {
                Image(
                    painter = painterResource(R.drawable.oobe_mascot),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (currentStep != OobeStep.Permissions) {
                    CommandLogButton(
                        attention = setupFailed || waitedTooLong,
                        expanded = terminalVisible,
                        onClick = {
                            terminalVisible = true
                            embeddedTerminalDefaultApplied = true
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = statusBarTop + 8.dp, end = 20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maxHeight - contentTop)
                    .clip(
                        RoundedCornerShape(
                            topStart = contentCornerRadius,
                            topEnd = contentCornerRadius
                        )
                    )
                    .background(BackgroundDark)
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                OobeStepIndicator(currentStep = currentStep, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "OobeStepHeader"
                ) { step ->
                    OobeStepHeader(step = step)
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    transitionSpec = { oobeStepTransition() },
                    label = "OobeStepCards"
                ) { step ->
                    when (step) {
                        OobeStep.Permissions -> CompactOobePermissionCards(permissionState, permissionActions)
                        OobeStep.Container -> CompactOobeTaskList(setupState.containerTasks)
                        OobeStep.Install -> CompactOobeTaskList(setupState.installTasks)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "OobeStepBottom"
                ) { step ->
                    OobeStepFooter(
                        step = step,
                        setupState = setupState,
                        storageGranted = permissionState.storageGranted,
                        onNext = onNext,
                        onRetry = onRetry
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (terminalVisible && currentStep != OobeStep.Permissions && !useEmbeddedTerminal) {
            TerminalOutputDialog(
                logs = setupState.commandLogs,
                onDismiss = {
                    terminalVisible = false
                    embeddedTerminalDefaultApplied = true
                }
            )
        }
    }
}

@Composable
private fun AdaptiveOobeFlowLayout(
    currentStep: OobeStep,
    setupState: OobeSetupState,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    commandLogAttention: Boolean,
    embeddedTerminalMode: Boolean,
    terminalExpanded: Boolean,
    showEmbeddedTerminal: Boolean,
    onTerminalToggle: () -> Unit,
    statusBarTop: Dp,
    safeDrawingLeft: Dp,
    safeDrawingRight: Dp,
    safeDrawingBottom: Dp,
    availableWidth: Dp
) {
    val heroWidth = oobeHeroWidthDp(availableWidth.value).dp
    val heroHorizontalPadding = if (heroWidth < 220.dp) 16.dp else 24.dp
    val workHorizontalPadding = if (availableWidth < 700.dp) 16.dp else 24.dp

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(OOBE_HERO_PANE_WEIGHT)
                .fillMaxHeight()
        ) {
            Image(
                painter = painterResource(R.drawable.oobe_mascot),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.08f),
                                0.42f to Color.Transparent,
                                1f to BackgroundDark.copy(alpha = 0.96f)
                            )
                        )
                    )
            )

            if (currentStep != OobeStep.Permissions && !embeddedTerminalMode) {
                CommandLogButton(
                    attention = commandLogAttention,
                    expanded = terminalExpanded,
                    onClick = onTerminalToggle,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = statusBarTop + 12.dp, end = 16.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = heroHorizontalPadding + safeDrawingLeft,
                        end = heroHorizontalPadding,
                        bottom = safeDrawingBottom + 24.dp
                    )
            ) {
                OobeStepIndicator(
                    currentStep = currentStep,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "AdaptiveOobeStepHeader"
                ) { step ->
                    OobeStepHeader(step = step, narrow = heroWidth < 220.dp, limitLines = true)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(OOBE_WORK_PANE_WEIGHT)
                .fillMaxHeight()
                .clip(
                    RoundedCornerShape(
                        topStart = 36.dp,
                        bottomStart = 36.dp
                    )
                )
                .background(BackgroundDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(
                        start = workHorizontalPadding,
                        top = statusBarTop + 16.dp,
                        end = workHorizontalPadding + safeDrawingRight,
                        bottom = safeDrawingBottom + 20.dp
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(
                            if (showEmbeddedTerminal) {
                                OOBE_WORK_CONTENT_WEIGHT
                            } else {
                                1f
                            }
                        )
                ) {
                    if (currentStep != OobeStep.Permissions && embeddedTerminalMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            CommandLogButton(
                                attention = commandLogAttention,
                                expanded = terminalExpanded,
                                onClick = onTerminalToggle
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    AnimatedContent(
                        targetState = currentStep,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        transitionSpec = { oobeStepTransition() },
                        label = "AdaptiveOobeStepCards"
                    ) { step ->
                        when (step) {
                            OobeStep.Permissions -> AdaptiveOobePermissionCards(permissionState, permissionActions)
                            OobeStep.Container -> AdaptiveOobeTaskList(setupState.containerTasks)
                            OobeStep.Install -> AdaptiveOobeTaskList(setupState.installTasks)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedContent(
                        targetState = currentStep,
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = { oobeStepTransition() },
                        label = "AdaptiveOobeStepBottom"
                    ) { step ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            OobeStepFooter(
                                step = step,
                                setupState = setupState,
                                storageGranted = permissionState.storageGranted,
                                onNext = onNext,
                                onRetry = onRetry,
                                nextButtonModifier = Modifier.widthIn(max = 520.dp),
                                setupFooterModifier = Modifier.widthIn(max = 560.dp)
                            )
                        }
                    }
                }

                if (showEmbeddedTerminal) {
                    EmbeddedTerminalOutputPanel(
                        logs = setupState.commandLogs,
                        onDismiss = onTerminalToggle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(OOBE_TERMINAL_PANE_WEIGHT)
                            .padding(top = 12.dp)
                    )
                }
            }
        }
    }
}

