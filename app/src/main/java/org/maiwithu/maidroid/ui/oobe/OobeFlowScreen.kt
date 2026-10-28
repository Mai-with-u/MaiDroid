package org.maiwithu.maidroid.ui.oobe

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.oobe.OobeSetupState
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.permission.PermissionActions
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.ui.screen.EmbeddedTerminalOutputPanel
import org.maiwithu.maidroid.ui.screen.TerminalOutputDialog
import org.maiwithu.maidroid.ui.theme.OobeBackground

// 配置容器或安装一直没完成时，过这么久把终端按钮展开成“遇到问题？”提醒用户看日志。
private const val SETUP_ATTENTION_DELAY_MS = 5 * 60 * 1000L

// 平板布局左右两栏的比例：左侧插画与步骤说明占 1，右侧工作区占 2；
// 右侧嵌入终端时，工作内容与终端再按 2 : 1 分高度。
internal const val OOBE_HERO_PANE_WEIGHT = 1f
internal const val OOBE_WORK_PANE_WEIGHT = 2f
internal const val OOBE_WORK_CONTENT_WEIGHT = 2f
internal const val OOBE_TERMINAL_PANE_WEIGHT = 1f

/** 宽度不小于 600dp 或横屏时用左右分栏的平板布局，否则用手机竖屏布局。 */
internal fun oobeUsesAdaptiveLayout(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600f || widthDp > heightDp

internal fun oobeHeroWidthDp(availableWidthDp: Float): Float =
    availableWidthDp.coerceAtLeast(0f) / 3f

/** 横屏且短边不小于 600dp（大平板横放）时，终端输出直接嵌在右侧工作区下方，而不是弹窗。 */
internal fun oobeUsesEmbeddedTerminal(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp && minOf(widthDp, heightDp) >= 600f

internal fun oobeShowsEmbeddedTerminal(
    embeddedMode: Boolean,
    currentStep: OobeStep,
    terminalVisible: Boolean
): Boolean = embeddedMode && currentStep != OobeStep.Permissions && terminalVisible

/** 终端是否展开，以及“嵌入模式默认展开一次”是否已经生效过（之后尊重用户手动收起）。 */
internal data class OobeTerminalPresentationState(
    val visible: Boolean,
    val embeddedDefaultApplied: Boolean
)

/**
 * 步骤或布局变化时决定终端的展开状态：
 * 回到权限步骤 -> 收起并重置；第一次进入嵌入模式 -> 默认展开一次；其余保持用户上次的选择。
 */
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

/**
 * 首次启动引导（OOBE）：授予权限 -> 配置容器 -> 安装 MaiBot。
 *
 * 只负责展示，步骤切换和安装动作由调用方（MaiDroidApp + MainViewModel）通过 onNext / onRetry 处理。
 * 手机竖屏是“上方插画 + 下方圆角内容区”，平板和横屏是“左侧插画与步骤说明 + 右侧工作区”。
 */
@Composable
internal fun OobeFlowScreen(
    currentStep: OobeStep,
    setupState: OobeSetupState,
    permissionState: PermissionState,
    permissionActions: PermissionActions,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(OobeBackground)
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val insets = with(density) {
            OobeInsets(
                statusBarTop = WindowInsets.statusBars.getTop(this).toDp(),
                safeLeft = WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp(),
                safeRight = WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp(),
                safeBottom = WindowInsets.safeDrawing.getBottom(this).toDp()
            )
        }
        val useAdaptiveLayout = oobeUsesAdaptiveLayout(maxWidth.value, maxHeight.value)
        val useEmbeddedTerminal = oobeUsesEmbeddedTerminal(maxWidth.value, maxHeight.value)
        val setupFailed = setupState.tasksFor(currentStep).hasFailedTask()
        val stepDone = when (currentStep) {
            OobeStep.Permissions -> true
            OobeStep.Container -> setupState.canInstall
            OobeStep.Install -> setupState.isComplete
        }
        var waitedTooLong by remember { mutableStateOf(false) }
        var terminalVisible by rememberSaveable { mutableStateOf(false) }
        var embeddedTerminalDefaultApplied by rememberSaveable { mutableStateOf(false) }
        val terminal = OobeTerminalControl(
            attention = setupFailed || waitedTooLong,
            expanded = terminalVisible,
            embeddedMode = useEmbeddedTerminal,
            showEmbedded = oobeShowsEmbeddedTerminal(useEmbeddedTerminal, currentStep, terminalVisible),
            onToggle = {
                terminalVisible = !terminalVisible
                embeddedTerminalDefaultApplied = true
            }
        )

        // 每进入一步重新计时：这一步既没完成也没失败，等满 5 分钟就提醒查看终端。
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

        val stepContent = OobeStepContent(currentStep, setupState, permissionState, permissionActions, onNext, onRetry)
        if (useAdaptiveLayout) {
            AdaptiveOobeLayout(
                content = stepContent,
                terminal = terminal,
                insets = insets,
                availableWidth = maxWidth
            )
        } else {
            CompactOobeLayout(
                content = stepContent,
                terminal = terminal,
                insets = insets,
                maxHeight = maxHeight
            )
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

// 两种布局都要用到的当前步骤数据和回调。
private class OobeStepContent(
    val step: OobeStep,
    val setupState: OobeSetupState,
    val permissionState: PermissionState,
    val permissionActions: PermissionActions,
    val onNext: () -> Unit,
    val onRetry: () -> Unit
)

// 终端按钮与嵌入式终端面板的显示状态。
private class OobeTerminalControl(
    val attention: Boolean,
    val expanded: Boolean,
    val embeddedMode: Boolean,
    val showEmbedded: Boolean,
    val onToggle: () -> Unit
)

private class OobeInsets(
    val statusBarTop: Dp,
    val safeLeft: Dp,
    val safeRight: Dp,
    val safeBottom: Dp
)

/**
 * 手机竖屏：上方三分之一是插画（右上角放终端按钮），下方是向上盖住插画 48dp 的圆角内容区，
 * 依次是步骤指示器、步骤标题、权限卡片或任务列表、底部按钮区。终端以弹窗形式打开。
 */
@Composable
private fun BoxScope.CompactOobeLayout(
    content: OobeStepContent,
    terminal: OobeTerminalControl,
    insets: OobeInsets,
    maxHeight: Dp
) {
    val step = content.step
    val headerHeight = maxHeight * 0.33f
    val contentCornerRadius = 48.dp
    val contentTop = maxOf(headerHeight - contentCornerRadius, 0.dp)

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

        if (step != OobeStep.Permissions) {
            CommandLogButton(
                attention = terminal.attention,
                expanded = terminal.expanded,
                onClick = terminal.onToggle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = insets.statusBarTop + 8.dp, end = 20.dp)
            )
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(maxHeight - contentTop)
            .clip(RoundedCornerShape(topStart = contentCornerRadius, topEnd = contentCornerRadius))
            .background(OobeBackground)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        OobeStepIndicator(currentStep = step, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(
            targetState = step,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = { oobeStepTransition() },
            label = "OobeStepHeader"
        ) { targetStep ->
            OobeStepHeader(step = targetStep)
        }

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(
            targetState = step,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = { oobeStepTransition() },
            label = "OobeStepCards"
        ) { targetStep ->
            when (targetStep) {
                OobeStep.Permissions -> CompactOobePermissionCards(content.permissionState, content.permissionActions)
                OobeStep.Container -> CompactOobeTaskList(content.setupState.containerTasks)
                OobeStep.Install -> CompactOobeTaskList(content.setupState.installTasks)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        AnimatedContent(
            targetState = step,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = { oobeStepTransition() },
            label = "OobeStepBottom"
        ) { targetStep ->
            OobeStepFooter(
                step = targetStep,
                setupState = content.setupState,
                storageGranted = content.permissionState.storageGranted,
                onNext = content.onNext,
                onRetry = content.onRetry
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 平板和横屏：左侧三分之一是插画，底部压着步骤指示器和步骤标题；右侧是圆角工作区，
 * 放卡片或任务列表、底部按钮区，大平板横屏时下方再嵌一块终端输出。
 */
@Composable
private fun AdaptiveOobeLayout(
    content: OobeStepContent,
    terminal: OobeTerminalControl,
    insets: OobeInsets,
    availableWidth: Dp
) {
    val step = content.step
    val heroWidth = oobeHeroWidthDp(availableWidth.value).dp
    val narrowHero = heroWidth < 220.dp
    val heroHorizontalPadding = if (narrowHero) 16.dp else 24.dp
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

            // 插画底部渐暗，让压在上面的步骤标题看得清。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to Color.Black.copy(alpha = 0.08f),
                                0.42f to Color.Transparent,
                                1f to OobeBackground.copy(alpha = 0.96f)
                            )
                        )
                    )
            )

            if (step != OobeStep.Permissions && !terminal.embeddedMode) {
                CommandLogButton(
                    attention = terminal.attention,
                    expanded = terminal.expanded,
                    onClick = terminal.onToggle,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = insets.statusBarTop + 12.dp, end = 16.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = heroHorizontalPadding + insets.safeLeft,
                        end = heroHorizontalPadding,
                        bottom = insets.safeBottom + 24.dp
                    )
            ) {
                OobeStepIndicator(currentStep = step, modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedContent(
                    targetState = step,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = { oobeStepTransition() },
                    label = "AdaptiveOobeStepHeader"
                ) { targetStep ->
                    OobeStepHeader(step = targetStep, narrow = narrowHero, limitLines = true)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(OOBE_WORK_PANE_WEIGHT)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 36.dp, bottomStart = 36.dp))
                .background(OobeBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = workHorizontalPadding,
                        top = insets.statusBarTop + 16.dp,
                        end = workHorizontalPadding + insets.safeRight,
                        bottom = insets.safeBottom + 20.dp
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (terminal.showEmbedded) OOBE_WORK_CONTENT_WEIGHT else 1f)
                ) {
                    // 嵌入终端模式下，终端开关放在工作区右上角，而不是插画上。
                    if (step != OobeStep.Permissions && terminal.embeddedMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            CommandLogButton(
                                attention = terminal.attention,
                                expanded = terminal.expanded,
                                onClick = terminal.onToggle
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    AnimatedContent(
                        targetState = step,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        transitionSpec = { oobeStepTransition() },
                        label = "AdaptiveOobeStepCards"
                    ) { targetStep ->
                        when (targetStep) {
                            OobeStep.Permissions -> AdaptiveOobePermissionCards(content.permissionState, content.permissionActions)
                            OobeStep.Container -> AdaptiveOobeTaskList(content.setupState.containerTasks)
                            OobeStep.Install -> AdaptiveOobeTaskList(content.setupState.installTasks)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    AnimatedContent(
                        targetState = step,
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = { oobeStepTransition() },
                        label = "AdaptiveOobeStepBottom"
                    ) { targetStep ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            OobeStepFooter(
                                step = targetStep,
                                setupState = content.setupState,
                                storageGranted = content.permissionState.storageGranted,
                                onNext = content.onNext,
                                onRetry = content.onRetry,
                                nextButtonModifier = Modifier.widthIn(max = 520.dp),
                                setupFooterModifier = Modifier.widthIn(max = 560.dp)
                            )
                        }
                    }
                }

                if (terminal.showEmbedded) {
                    EmbeddedTerminalOutputPanel(
                        logs = content.setupState.commandLogs,
                        onDismiss = terminal.onToggle,
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
