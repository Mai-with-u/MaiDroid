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
import org.maiwithu.maidroid.oobe.OobeTaskState
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.component.DownloadResourceCard
import org.maiwithu.maidroid.ui.component.OobeStepIndicator
import org.maiwithu.maidroid.ui.component.PermissionCard
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
fun OobeFlowScreen(
    currentStep: Int,
    setupState: OobeSetupState = OobeSetupState.preview(),
    storagePermissionGranted: Boolean = false,
    notificationPermissionGranted: Boolean = false,
    batteryOptimizationGranted: Boolean = false,
    onStorageAuthorize: () -> Unit = {},
    onNotificationAuthorize: () -> Unit = {},
    onBatteryOptimizationAuthorize: () -> Unit = {},
    onAutoStartAuthorize: () -> Unit = {},
    onTaskLockAuthorize: () -> Unit = {},
    onAccessibilityAuthorize: () -> Unit = {},
    onDeviceAdminAuthorize: () -> Unit = {},
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
                storagePermissionGranted = storagePermissionGranted,
                notificationPermissionGranted = notificationPermissionGranted,
                batteryOptimizationGranted = batteryOptimizationGranted,
                onStorageAuthorize = onStorageAuthorize,
                onNotificationAuthorize = onNotificationAuthorize,
                onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
                onAutoStartAuthorize = onAutoStartAuthorize,
                onTaskLockAuthorize = onTaskLockAuthorize,
                onAccessibilityAuthorize = onAccessibilityAuthorize,
                onDeviceAdminAuthorize = onDeviceAdminAuthorize,
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

                OobeStepIndicator(currentStep = currentStep, modifier = Modifier.fillMaxWidth())

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
                        0 -> PermissionCards(
                            storagePermissionGranted = storagePermissionGranted,
                            notificationPermissionGranted = notificationPermissionGranted,
                            batteryOptimizationGranted = batteryOptimizationGranted,
                            onStorageAuthorize = onStorageAuthorize,
                            onNotificationAuthorize = onNotificationAuthorize,
                            onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
                            onAutoStartAuthorize = onAutoStartAuthorize,
                            onTaskLockAuthorize = onTaskLockAuthorize,
                            onAccessibilityAuthorize = onAccessibilityAuthorize,
                            onDeviceAdminAuthorize = onDeviceAdminAuthorize
                        )

                        1 -> SetupCards(setupState.containerTasks)
                        else -> SetupCards(setupState.installTasks)
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
                            text = if (storagePermissionGranted) "下一步" else "请先授权",
                            enabled = storagePermissionGranted,
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
    storagePermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    onStorageAuthorize: () -> Unit,
    onNotificationAuthorize: () -> Unit,
    onBatteryOptimizationAuthorize: () -> Unit,
    onAutoStartAuthorize: () -> Unit,
    onTaskLockAuthorize: () -> Unit,
    onAccessibilityAuthorize: () -> Unit,
    onDeviceAdminAuthorize: () -> Unit,
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
                            0 -> AdaptivePermissionCards(
                                storagePermissionGranted = storagePermissionGranted,
                                notificationPermissionGranted = notificationPermissionGranted,
                                batteryOptimizationGranted = batteryOptimizationGranted,
                                onStorageAuthorize = onStorageAuthorize,
                                onNotificationAuthorize = onNotificationAuthorize,
                                onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
                                onAutoStartAuthorize = onAutoStartAuthorize,
                                onTaskLockAuthorize = onTaskLockAuthorize,
                                onAccessibilityAuthorize = onAccessibilityAuthorize,
                                onDeviceAdminAuthorize = onDeviceAdminAuthorize
                            )

                            1 -> AdaptiveSetupCards(setupState.containerTasks)
                            else -> AdaptiveSetupCards(setupState.installTasks)
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
                            storagePermissionGranted = storagePermissionGranted,
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
private fun AdaptivePermissionCards(
    storagePermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    onStorageAuthorize: () -> Unit,
    onNotificationAuthorize: () -> Unit,
    onBatteryOptimizationAuthorize: () -> Unit,
    onAutoStartAuthorize: () -> Unit,
    onTaskLockAuthorize: () -> Unit,
    onAccessibilityAuthorize: () -> Unit,
    onDeviceAdminAuthorize: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 760.dp) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    StoragePermissionCard(
                        granted = storagePermissionGranted,
                        onAuthorize = onStorageAuthorize
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    BackgroundKeepAliveCard(
                        notificationPermissionGranted = notificationPermissionGranted,
                        batteryOptimizationGranted = batteryOptimizationGranted,
                        onNotificationAuthorize = onNotificationAuthorize,
                        onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
                        onAutoStartAuthorize = onAutoStartAuthorize,
                        onTaskLockAuthorize = onTaskLockAuthorize,
                        onAccessibilityAuthorize = onAccessibilityAuthorize,
                        onDeviceAdminAuthorize = onDeviceAdminAuthorize
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                StoragePermissionCard(
                    granted = storagePermissionGranted,
                    onAuthorize = onStorageAuthorize
                )

                Spacer(modifier = Modifier.height(14.dp))

                BackgroundKeepAliveCard(
                    notificationPermissionGranted = notificationPermissionGranted,
                    batteryOptimizationGranted = batteryOptimizationGranted,
                    onNotificationAuthorize = onNotificationAuthorize,
                    onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
                    onAutoStartAuthorize = onAutoStartAuthorize,
                    onTaskLockAuthorize = onTaskLockAuthorize,
                    onAccessibilityAuthorize = onAccessibilityAuthorize,
                    onDeviceAdminAuthorize = onDeviceAdminAuthorize
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StoragePermissionCard(
    granted: Boolean,
    onAuthorize: () -> Unit
) {
    PermissionCard(
        iconRes = R.drawable.ic_storage_hdd,
        title = "存储权限",
        description = "用于解包容器、保存 MaiBot 配置和访问外部文件。",
        required = true,
        granted = granted,
        onAuthorize = onAuthorize
    )
}

@Composable
private fun AdaptiveSetupCards(tasks: List<OobeTaskState>) {
    val gridState = rememberLazyGridState()
    val focusIndex = remember(tasks) {
        tasks.indexOfFirst { it.status == OobeTaskStatus.Running }.takeIf { it >= 0 }
            ?: tasks.indexOfFirst { it.status != OobeTaskStatus.Done }.takeIf { it >= 0 }
            ?: tasks.lastIndex.takeIf { it >= 0 }
    }

    LaunchedEffect(focusIndex) {
        focusIndex?.let { gridState.animateScrollToItem(it) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 300.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        gridItemsIndexed(tasks) { _, task ->
            DownloadResourceCard(
                iconRes = task.iconRes(),
                title = task.title,
                description = task.cardDescription(),
                status = task.status,
                height = 76.dp
            )
        }
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

@Composable
private fun PermissionCards(
    storagePermissionGranted: Boolean,
    notificationPermissionGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    onStorageAuthorize: () -> Unit,
    onNotificationAuthorize: () -> Unit,
    onBatteryOptimizationAuthorize: () -> Unit,
    onAutoStartAuthorize: () -> Unit,
    onTaskLockAuthorize: () -> Unit,
    onAccessibilityAuthorize: () -> Unit,
    onDeviceAdminAuthorize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        PermissionCard(
            iconRes = R.drawable.ic_storage_hdd,
            title = "存储权限",
            description = "用于解包容器、保存 MaiBot 配置和访问外部文件。",
            required = true,
            granted = storagePermissionGranted,
            onAuthorize = onStorageAuthorize
        )

        Spacer(modifier = Modifier.height(20.dp))

        BackgroundKeepAliveCard(
            notificationPermissionGranted = notificationPermissionGranted,
            batteryOptimizationGranted = batteryOptimizationGranted,
            onNotificationAuthorize = onNotificationAuthorize,
            onBatteryOptimizationAuthorize = onBatteryOptimizationAuthorize,
            onAutoStartAuthorize = onAutoStartAuthorize,
            onTaskLockAuthorize = onTaskLockAuthorize,
            onAccessibilityAuthorize = onAccessibilityAuthorize,
            onDeviceAdminAuthorize = onDeviceAdminAuthorize
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun BackgroundKeepAliveCard(
    notificationPermissionGranted: Boolean,
    batteryOptimizationGranted: Boolean,
    onNotificationAuthorize: () -> Unit,
    onBatteryOptimizationAuthorize: () -> Unit,
    onAutoStartAuthorize: () -> Unit,
    onTaskLockAuthorize: () -> Unit,
    onAccessibilityAuthorize: () -> Unit,
    onDeviceAdminAuthorize: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(SurfaceDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(71.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Orange400),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_job_run),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(33.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "后台保活",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        lineHeight = 24.sp
                    )

                    Spacer(modifier = Modifier.width(5.dp))

                    KeepAliveTag(
                        text = "可选",
                        backgroundColor = Orange400,
                        width = 48.dp,
                        textSize = 12
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "MaiSaka 需要后台权限才能在切换到其他应用时保持后台运行；这个设置是可选项，你可以视情况开启一部分。",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(272.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1E))
                .padding(8.dp)
        ) {
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "通知保活",
                granted = notificationPermissionGranted,
                onAuthorize = onNotificationAuthorize
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "关闭电池优化",
                granted = batteryOptimizationGranted,
                onAuthorize = onBatteryOptimizationAuthorize
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "开启自启动&后台活动",
                onAuthorize = onAutoStartAuthorize
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "推荐",
                title = "多任务加锁",
                onAuthorize = onTaskLockAuthorize
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "不建议",
                title = "无障碍服务",
                actionText = "你确定？！",
                destructive = true,
                onAuthorize = onAccessibilityAuthorize
            )
            KeepAliveDivider()
            KeepAlivePermissionRow(
                tagText = "不建议",
                title = "设备管理员",
                actionText = "你确定？！",
                destructive = true,
                onAuthorize = onDeviceAdminAuthorize
            )
        }
    }
}

@Composable
private fun KeepAlivePermissionRow(
    tagText: String,
    title: String,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
    granted: Boolean = false,
    actionText: String = "去授权",
    destructive: Boolean = false
) {
    val actionLabel = if (granted) "已授权" else actionText
    val actionColor = if (granted) Orange500 else Color(0xFF808080)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !granted, onClick = onAuthorize)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeepAliveTag(
            text = tagText,
            backgroundColor = if (destructive) Color(0xFFE90F0F) else Orange500,
            width = if (destructive) 42.dp else 32.dp,
            textSize = 10
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 18.sp
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = actionLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = actionColor,
                maxLines = 1,
                lineHeight = 14.sp
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = actionColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun KeepAliveDivider() {
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
    Spacer(modifier = Modifier.height(3.dp))
}

@Composable
private fun KeepAliveTag(
    text: String,
    backgroundColor: Color,
    width: androidx.compose.ui.unit.Dp,
    textSize: Int
) {
    Box(
        modifier = Modifier
            .size(width = width, height = 18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = textSize.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            lineHeight = textSize.sp
        )
    }
}

@Composable
private fun SetupCards(tasks: List<OobeTaskState>) {
    val listState = rememberLazyListState()
    val focusIndex = remember(tasks) {
        tasks.indexOfFirst { it.status == OobeTaskStatus.Running }.takeIf { it >= 0 }
            ?: tasks.indexOfFirst { it.status != OobeTaskStatus.Done }.takeIf { it >= 0 }
            ?: tasks.lastIndex.takeIf { it >= 0 }
    }

    LaunchedEffect(focusIndex) {
        focusIndex?.let { listState.animateScrollToItem(it) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(tasks) { _, task ->
            DownloadResourceCard(
                iconRes = task.iconRes(),
                title = task.title,
                description = task.cardDescription(),
                status = task.status,
                height = 76.dp
            )
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
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

private fun List<OobeTaskState>.hasFailedTask(): Boolean =
    any { it.status == OobeTaskStatus.Failed }

@DrawableRes
private fun OobeTaskState.iconRes(): Int = when (title) {
    "Debian rootfs" -> R.drawable.oobe_icon_debian_rootfs
    "Termux bootstrap" -> R.drawable.oobe_icon_termux_bootstrap
    "proot" -> R.drawable.oobe_icon_proot
    "Git" -> R.drawable.oobe_icon_git
    "curl / CA" -> R.drawable.oobe_icon_curl_ca
    else -> when (kind) {
        OobeTaskKind.Container -> R.drawable.oobe_icon_debian_rootfs
        OobeTaskKind.Source -> R.drawable.oobe_icon_git
        OobeTaskKind.Dependency -> R.drawable.oobe_icon_python
        OobeTaskKind.Agreement -> R.drawable.oobe_icon_curl_ca
        OobeTaskKind.WebUi -> R.drawable.oobe_icon_webui
    }
}

private fun OobeTaskState.cardDescription(): String = when (title) {
    "Debian rootfs" -> "准备 Debian 根文件系统"
    "Termux bootstrap" -> "准备基础运行环境"
    "proot" -> "准备容器启动器"
    "Git" -> "安装源码下载工具"
    "curl / CA" -> "配置 HTTPS 下载能力"
    else -> when (kind) {
        OobeTaskKind.Container -> description
        OobeTaskKind.Source -> "下载 MaiBot 源码"
        OobeTaskKind.Dependency -> "安装 Python 运行依赖"
        OobeTaskKind.Agreement -> "自动确认运行协议"
        OobeTaskKind.WebUi -> "启动 MaiBot WebUI"
    }
}

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
