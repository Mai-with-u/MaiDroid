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
import org.maiwithu.maidroid.ui.oobe.OobeStepIndicator
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
    currentStep: Int,
    terminalVisible: Boolean
): Boolean = embeddedMode && currentStep > 0 && terminalVisible

internal data class OobeTerminalPresentationState(
    val visible: Boolean,
    val embeddedDefaultApplied: Boolean
)

internal fun oobeTerminalStateForEnvironment(
    currentStep: Int,
    embeddedMode: Boolean,
    visible: Boolean,
    embeddedDefaultApplied: Boolean
): OobeTerminalPresentationState = when {
    currentStep <= 0 -> OobeTerminalPresentationState(
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
    currentStep: Int,
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
        val currentTasks = when (currentStep) {
            1 -> setupState.containerTasks
            2 -> setupState.installTasks
            else -> emptyList()
        }
        val setupFailed = currentTasks.hasFailedTask()
        val stepDone = when (currentStep) {
            1 -> setupState.canInstall
            2 -> setupState.isComplete
            else -> true
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
            if (currentStep > 0 && !stepDone && !setupFailed) {
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

                if (currentStep > 0) {
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

                OobeStepIndicator(currentStep = OobeStep.entries[currentStep], modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "OobeStepHeader"
                ) { step ->
                    when (step) {
                        0 -> StepHeaderContent(
                            title = "授予权限",
                            description = "MaiSaka需要以下权限才能运行~\n可选权限也可以不给，如果你确定你在做什么的话"
                        )

                        1 -> StepHeaderContent(
                            title = "配置容器",
                            description = "正在自动下载运行环境，以给予麦麦一个新家\n请保持网络连接，下载完成后麦麦会自己装~"
                        )

                        else -> StepHeaderContent(
                            title = "安装 MaiBot",
                            description = "麦麦正在装修并入住新家……，请耐心等待"
                        )
                    }
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
                        0 -> CompactOobePermissionCards(permissionState, permissionActions)

                        1 -> CompactOobeTaskList(setupState.containerTasks)
                        else -> CompactOobeTaskList(setupState.installTasks)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "OobeStepBottom"
                ) { step ->
                    when (step) {
                        0 -> NextButton(
                            text = if (permissionState.storageGranted) "下一步" else "请先授权",
                            enabled = permissionState.storageGranted,
                            onNext = onNext
                        )

                        1 -> SetupFooter(
                            footerText = setupState.footerText,
                            canProceed = setupState.canInstall,
                            proceedText = "开始安装",
                            waitingText = "重新配置",
                            waitingEnabled = setupState.containerTasks.hasFailedTask(),
                            onProceed = onNext,
                            onRetry = onRetry
                        )

                        else -> {
                            val installRunning = setupState.installTasks.any {
                                it.status == OobeTaskStatus.Running
                            }
                            val installFailed = setupState.installTasks.hasFailedTask()
                            SetupFooter(
                                footerText = setupState.footerText,
                                canProceed = setupState.isComplete,
                                proceedText = "进入 MaiDroid",
                                waitingText = if (installFailed) "重试安装" else "请稍候",
                                waitingEnabled = installFailed && !installRunning,
                                onProceed = onNext,
                                onRetry = onRetry
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        if (terminalVisible && currentStep > 0 && !useEmbeddedTerminal) {
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
    currentStep: Int,
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

            if (currentStep > 0 && !embeddedTerminalMode) {
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
                    currentStep = OobeStep.entries[currentStep],
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedContent(
                    targetState = currentStep,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "AdaptiveOobeStepHeader"
                ) { step ->
                    when (step) {
                        0 -> AdaptiveStepHeaderContent(
                            title = "授予权限",
                            description = "MaiSaka需要以下权限才能运行~\n可选权限也可以不给，如果你确定你在做什么的话",
                            narrow = heroWidth < 220.dp
                        )

                        1 -> AdaptiveStepHeaderContent(
                            title = "配置容器",
                            description = "正在自动下载运行环境，以给予麦麦一个新家\n请保持网络连接，下载完成后麦麦会自己装~",
                            narrow = heroWidth < 220.dp
                        )

                        else -> AdaptiveStepHeaderContent(
                            title = "安装 MaiBot",
                            description = "麦麦正在装修并入住新家……，请耐心等待",
                            narrow = heroWidth < 220.dp
                        )
                    }
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
                    if (currentStep > 0 && embeddedTerminalMode) {
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
                            0 -> AdaptiveOobePermissionCards(permissionState, permissionActions)

                            1 -> AdaptiveOobeTaskList(setupState.containerTasks)
                            else -> AdaptiveOobeTaskList(setupState.installTasks)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedContent(
                        targetState = currentStep,
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = { oobeStepTransition() },
                        label = "AdaptiveOobeStepBottom"
                    ) { step ->
                        AdaptiveOobeFooter(
                            currentStep = step,
                            setupState = setupState,
                            storagePermissionGranted = permissionState.storageGranted,
                            onNext = onNext,
                            onRetry = onRetry
                        )
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

@Composable
private fun AdaptiveStepHeaderContent(
    title: String,
    description: String,
    narrow: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = if (narrow) 20.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = Orange500,
            lineHeight = if (narrow) 24.sp else 27.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(if (narrow) 7.dp else 10.dp))

        Text(
            text = description,
            fontSize = if (narrow) 12.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            lineHeight = if (narrow) 15.sp else 17.sp,
            maxLines = if (narrow) 6 else 5,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AdaptiveOobeFooter(
    currentStep: Int,
    setupState: OobeSetupState,
    storagePermissionGranted: Boolean,
    onNext: () -> Unit,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd
    ) {
        when (currentStep) {
            0 -> NextButton(
                text = if (storagePermissionGranted) "下一步" else "请先授权",
                enabled = storagePermissionGranted,
                onNext = onNext,
                modifier = Modifier.widthIn(max = 520.dp)
            )

            1 -> SetupFooter(
                footerText = setupState.footerText,
                canProceed = setupState.canInstall,
                proceedText = "开始安装",
                waitingText = "重新配置",
                waitingEnabled = setupState.containerTasks.hasFailedTask(),
                onProceed = onNext,
                onRetry = onRetry,
                modifier = Modifier.widthIn(max = 560.dp)
            )

            else -> {
                val installRunning = setupState.installTasks.any {
                    it.status == OobeTaskStatus.Running
                }
                val installFailed = setupState.installTasks.hasFailedTask()
                SetupFooter(
                    footerText = setupState.footerText,
                    canProceed = setupState.isComplete,
                    proceedText = "进入 MaiDroid",
                    waitingText = if (installFailed) "重试安装" else "请稍候",
                    waitingEnabled = installFailed && !installRunning,
                    onProceed = onNext,
                    onRetry = onRetry,
                    modifier = Modifier.widthIn(max = 560.dp)
                )
            }
        }
    }
}

@Composable
private fun CommandLogButton(
    attention: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showLabel = attention || expanded
    val buttonWidth by animateDpAsState(
        targetValue = if (showLabel) 132.dp else 44.dp,
        animationSpec = spring(
            dampingRatio = 0.78f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CommandLogButtonWidth"
    )

    Row(
        modifier = modifier
            .width(buttonWidth)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (expanded) {
                    Orange500.copy(alpha = 0.18f)
                } else {
                    SurfaceDark.copy(alpha = 0.82f)
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (showLabel) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (showLabel) Arrangement.Start else Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Code,
            contentDescription = if (expanded) "收起终端输出" else "查看终端输出",
            tint = Orange500,
            modifier = Modifier.size(24.dp)
        )

        if (showLabel) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) "收起终端" else "遇到问题？",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StepHeaderContent(
    title: String,
    description: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Orange500,
            lineHeight = 27.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = description,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            lineHeight = 17.sp
        )
    }
}

private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.oobeStepTransition() =
    if (targetState > initialState) {
        slideInHorizontally(
            animationSpec = spring(
                dampingRatio = 0.84f,
                stiffness = Spring.StiffnessMediumLow
            )
        ) { it }.togetherWith(
            slideOutHorizontally(
                animationSpec = spring(
                    dampingRatio = 0.84f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { -it }
        ) using SizeTransform(clip = true)
    } else {
        slideInHorizontally(
            animationSpec = spring(
                dampingRatio = 0.84f,
                stiffness = Spring.StiffnessMediumLow
            )
        ) { -it }.togetherWith(
            slideOutHorizontally(
                animationSpec = spring(
                    dampingRatio = 0.84f,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { it }
        ) using SizeTransform(clip = true)
    }

private fun List<OobeTask>.hasFailedTask(): Boolean =
    any { it.status == OobeTaskStatus.Failed }

@Composable
private fun NextButton(
    text: String = "下一步",
    enabled: Boolean = true,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onNext,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Orange400,
            contentColor = TextOnOrange,
            disabledContainerColor = Color.White.copy(alpha = 0.12f),
            disabledContentColor = TextSecondary
        )
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun SetupFooter(
    footerText: String,
    canProceed: Boolean,
    proceedText: String,
    waitingText: String,
    waitingEnabled: Boolean = true,
    onProceed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(SurfaceDark)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WaitingRing()

            Spacer(modifier = Modifier.size(14.dp))

            Text(
                text = footerText,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = TextSecondary,
                lineHeight = 17.sp
            )
        }

        Button(
            onClick = if (canProceed) onProceed else onRetry,
            enabled = canProceed || waitingEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange400,
                contentColor = TextOnOrange,
                disabledContainerColor = Color.White.copy(alpha = 0.12f),
                disabledContentColor = TextSecondary
            )
        ) {
            Text(
                text = if (canProceed) proceedText else waitingText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun WaitingRing() {
    val transition = rememberInfiniteTransition(label = "WaitingRingTransition")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaitingRingRotation"
    )

    Canvas(
        modifier = Modifier
            .size(24.dp)
            .rotate(rotation)
            .clip(CircleShape)
    ) {
        val stroke = 2.dp.toPx()
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val arcTopLeft = Offset(stroke / 2, stroke / 2)

        drawArc(
            color = Orange500,
            startAngle = -20f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = Orange500,
            startAngle = 160f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
