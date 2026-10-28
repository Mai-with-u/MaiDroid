package org.maiwithu.maidroid.ui.oobe

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.oobe.OobeSetupState
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.oobe.OobeTask
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.BrandOrangeMuted
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.OobeSurface
import org.maiwithu.maidroid.ui.theme.OobeTextPrimary
import org.maiwithu.maidroid.ui.theme.OobeTextSecondary

// OOBE 手机布局和平板布局共用的部件：步骤标题、底部按钮区、“遇到问题？”终端按钮、步骤切换动画。

private val OobeStep.title: String
    get() = when (this) {
        OobeStep.Permissions -> "授予权限"
        OobeStep.Container -> "配置容器"
        OobeStep.Install -> "安装 MaiBot"
    }

private val OobeStep.description: String
    get() = when (this) {
        OobeStep.Permissions -> "MaiSaka需要以下权限才能运行~\n可选权限也可以不给，如果你确定你在做什么的话"
        OobeStep.Container -> "正在自动下载运行环境，以给予麦麦一个新家\n请保持网络连接，下载完成后麦麦会自己装~"
        OobeStep.Install -> "麦麦正在装修并入住新家……，请耐心等待"
    }

/** 当前步骤显示的任务卡片：权限步骤没有任务卡片。 */
internal fun OobeSetupState.tasksFor(step: OobeStep): List<OobeTask> = when (step) {
    OobeStep.Permissions -> emptyList()
    OobeStep.Container -> containerTasks
    OobeStep.Install -> installTasks
}

internal fun List<OobeTask>.hasFailedTask(): Boolean =
    any { it.status == OobeTaskStatus.Failed }

/**
 * 步骤标题和说明。手机布局文字不限行；平板布局的左栏较窄，限制行数并在左栏窄于 220dp 时缩小字号。
 */
@Composable
internal fun OobeStepHeader(
    step: OobeStep,
    narrow: Boolean = false,
    limitLines: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = step.title,
            fontSize = if (narrow) 20.sp else 22.sp,
            fontWeight = FontWeight.Bold,
            color = BrandOrange,
            lineHeight = if (narrow) 24.sp else 27.sp,
            maxLines = if (limitLines) 2 else Int.MAX_VALUE,
            overflow = if (limitLines) TextOverflow.Ellipsis else TextOverflow.Clip
        )

        Spacer(modifier = Modifier.height(if (narrow) 7.dp else 10.dp))

        Text(
            text = step.description,
            fontSize = if (narrow) 12.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            color = OobeTextPrimary,
            lineHeight = if (narrow) 15.sp else 17.sp,
            maxLines = when {
                !limitLines -> Int.MAX_VALUE
                narrow -> 6
                else -> 5
            },
            overflow = if (limitLines) TextOverflow.Ellipsis else TextOverflow.Clip
        )
    }
}

/**
 * 每一步底部的按钮区：
 * 权限步骤 -> 存储授权后才能点“下一步”；
 * 配置容器 -> 容器就绪时“开始安装”，失败时“重新配置”；
 * 安装 MaiBot -> 完成时“进入 MaiDroid”，失败且没有任务在跑时“重试安装”，否则“请稍候”。
 * 平板布局通过两个 modifier 限制按钮最大宽度。
 */
@Composable
internal fun OobeStepFooter(
    step: OobeStep,
    setupState: OobeSetupState,
    storageGranted: Boolean,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    nextButtonModifier: Modifier = Modifier,
    setupFooterModifier: Modifier = Modifier
) {
    when (step) {
        OobeStep.Permissions -> NextButton(
            text = if (storageGranted) "下一步" else "请先授权",
            enabled = storageGranted,
            onNext = onNext,
            modifier = nextButtonModifier
        )

        OobeStep.Container -> SetupFooter(
            footerText = setupState.footerText,
            canProceed = setupState.canInstall,
            proceedText = "开始安装",
            waitingText = "重新配置",
            waitingEnabled = setupState.containerTasks.hasFailedTask(),
            onProceed = onNext,
            onRetry = onRetry,
            modifier = setupFooterModifier
        )

        OobeStep.Install -> {
            val installRunning = setupState.installTasks.any { it.status == OobeTaskStatus.Running }
            val installFailed = setupState.installTasks.hasFailedTask()
            SetupFooter(
                footerText = setupState.footerText,
                canProceed = setupState.isComplete,
                proceedText = "进入 MaiDroid",
                waitingText = if (installFailed) "重试安装" else "请稍候",
                waitingEnabled = installFailed && !installRunning,
                onProceed = onNext,
                onRetry = onRetry,
                modifier = setupFooterModifier
            )
        }
    }
}

/**
 * 打开或收起终端输出的按钮。平时只显示图标；出错或等太久（attention）时展开成“遇到问题？”，
 * 终端已展开（expanded）时显示“收起终端”。
 */
@Composable
internal fun CommandLogButton(
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
                    BrandOrange.copy(alpha = 0.18f)
                } else {
                    OobeSurface.copy(alpha = 0.82f)
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
            tint = BrandOrange,
            modifier = Modifier.size(24.dp)
        )

        if (showLabel) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (expanded) "收起终端" else "遇到问题？",
                color = OobeTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 步骤切换动画：前进时新内容从右边推进来，后退时从左边推进来。 */
internal fun AnimatedContentTransitionScope<OobeStep>.oobeStepTransition() =
    horizontalSlideTransition(forward = targetState > initialState, dampingRatio = 0.84f)

@Composable
private fun NextButton(
    text: String,
    enabled: Boolean,
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
            containerColor = BrandOrangeMuted,
            contentColor = OnBrandOrange,
            disabledContainerColor = Color.White.copy(alpha = 0.12f),
            disabledContentColor = OobeTextSecondary
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

// 配置容器、安装 MaiBot 两步的页脚：转圈 + 当前进展说明，下面一个按钮。
// 能继续时按钮执行 onProceed，否则执行 onRetry（waitingEnabled 为 false 时按钮禁用）。
@Composable
private fun SetupFooter(
    footerText: String,
    canProceed: Boolean,
    proceedText: String,
    waitingText: String,
    waitingEnabled: Boolean,
    onProceed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(OobeSurface)
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
                color = OobeTextSecondary,
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
                containerColor = BrandOrangeMuted,
                contentColor = OnBrandOrange,
                disabledContainerColor = Color.White.copy(alpha = 0.12f),
                disabledContentColor = OobeTextSecondary
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

// 两段对称圆弧持续旋转的等待标志。
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
            color = BrandOrange,
            startAngle = -20f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = BrandOrange,
            startAngle = 160f,
            sweepAngle = 72f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}
