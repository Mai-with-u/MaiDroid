package org.maiwithu.maidroid.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        val statusBarTop = with(density) {
            WindowInsets.statusBars.getTop(this).toDp()
        }
        val headerHeight = maxHeight * 0.33f
        val contentCornerRadius = 48.dp
        val contentOverlap = contentCornerRadius
        val contentTop = maxOf(headerHeight - contentOverlap, 0.dp)

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
                    logs = setupState.commandLogs,
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
                .clip(RoundedCornerShape(topStart = contentCornerRadius, topEnd = contentCornerRadius))
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
                        description = "MaiSaka 需要以下权限才能完成自动部署。必选权限通过后才会进入容器配置。"
                    )

                    1 -> StepHeaderContent(
                        title = "配置容器",
                        description = "正在检查 Debian rootfs、Termux bootstrap、proot 启动器和系统依赖。"
                    )

                    else -> StepHeaderContent(
                        title = "安装 MaiBot",
                        description = "将按 installation.md 自动 clone、安装 uv 依赖，并等待 MaiBot WebUI 启动。"
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
                        onProceed = onNext,
                        onRetry = onRetry
                    )
                    else -> {
                        val installRunning = setupState.installTasks.any {
                            it.status == OobeTaskStatus.Running
                        }
                        SetupFooter(
                            footerText = setupState.footerText,
                            canProceed = setupState.isComplete,
                            proceedText = "进入 MaiDroid",
                            waitingText = if (installRunning) "请稍候" else "重试安装",
                            waitingEnabled = !installRunning,
                            onProceed = onNext,
                            onRetry = onRetry
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CommandLogButton(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    var showLogs by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(SurfaceDark.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = { showLogs = true }) {
            Icon(
                imageVector = Icons.Outlined.Code,
                contentDescription = "查看容器输出",
                tint = Orange500,
                modifier = Modifier.size(24.dp)
            )
        }
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = {
                Text(
                    text = "容器命令输出",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                CommandLogViewer(logs = logs)
            },
            confirmButton = {
                Button(
                    onClick = { showLogs = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange400,
                        contentColor = TextOnOrange
                    )
                ) {
                    Text(text = "关闭", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = SurfaceDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary
        )
    }
}

@Composable
private fun CommandLogViewer(logs: List<String>) {
    val scrollState = rememberScrollState()
    val logText = remember(logs) {
        if (logs.isEmpty()) {
            "暂无命令输出，点击“重新配置”后会实时显示。"
        } else {
            logs.joinToString(separator = "\n") { line -> line.ifBlank { " " } }
        }
    }

    LaunchedEffect(logText) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.34f))
            .verticalScroll(scrollState)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        SelectionContainer {
            Text(
                text = logText,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = if (logs.isEmpty()) 12.sp else 11.sp,
                lineHeight = if (logs.isEmpty()) 16.sp else 15.sp
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
                icon = when (task.kind) {
                    OobeTaskKind.Container -> Icons.Outlined.Inventory2
                    OobeTaskKind.Source -> Icons.Outlined.Code
                    OobeTaskKind.Dependency -> Icons.Outlined.Settings
                    OobeTaskKind.Agreement -> Icons.Outlined.Code
                    OobeTaskKind.WebUi -> Icons.Outlined.Settings
                },
                title = task.title,
                description = task.description,
                progress = task.progress,
                progressText = task.progressText,
                statusText = task.statusText,
                status = task.status,
                height = 121.dp
            )
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

private fun androidx.compose.animation.AnimatedContentTransitionScope<Int>.oobeStepTransition() =
    if (targetState > initialState) {
        (slideInHorizontally { it / 4 } + fadeIn())
            .togetherWith(slideOutHorizontally { -it / 4 } + fadeOut())
    } else {
        (slideInHorizontally { -it / 4 } + fadeIn())
            .togetherWith(slideOutHorizontally { it / 4 } + fadeOut())
    }

@Composable
private fun NextButton(
    text: String = "下一步",
    enabled: Boolean = true,
    onNext: () -> Unit
) {
    Button(
        onClick = onNext,
        enabled = enabled,
        modifier = Modifier
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
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
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
                contentColor = TextOnOrange
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
