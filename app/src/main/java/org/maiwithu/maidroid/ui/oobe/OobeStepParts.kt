package org.maiwithu.maidroid.ui.oobe

import android.os.Build
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.oobe.OobeSetupState
import org.maiwithu.maidroid.oobe.OobeStep
import org.maiwithu.maidroid.oobe.OobeTask
import org.maiwithu.maidroid.oobe.OobeTaskStatus
import org.maiwithu.maidroid.ui.component.InteractiveHighlight
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.computeLiquidButtonDragTransform
import org.maiwithu.maidroid.ui.component.horizontalSlideTransition
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.component.resolveLiquidBackdrop
import org.maiwithu.maidroid.ui.diagnostics.FailureNotice
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.BrandOrangeMuted
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.GlassFallbackSurface
import org.maiwithu.maidroid.ui.theme.GlassShadow
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.OobeBackground
import org.maiwithu.maidroid.ui.theme.OobeSurface
import org.maiwithu.maidroid.ui.theme.OobeTextPrimary
import org.maiwithu.maidroid.ui.theme.OobeTextSecondary
import org.maiwithu.maidroid.ui.theme.TextSizes

// OOBE 手机布局和平板布局共用的部件：步骤标题、底部按钮区、“遇到问题？”终端按钮、步骤切换动画。

private val OobeStep.title: String
    get() = when (this) {
        OobeStep.Permissions -> "授予权限"
        OobeStep.Container -> "配置容器"
        OobeStep.Install -> "安装 MaiBot"
    }

private val OobeStep.description: String
    get() = when (this) {
        OobeStep.Permissions -> "请先开启消息通知权限以维持容器后台保活~\n其余权限均为可选，之后随时可在设置里调整"
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
 * 权限步骤 -> 开启必选消息通知权限后才能点“下一步”；
 * 配置容器 -> 容器就绪时“开始安装”，失败时“重新配置”；
 * 安装 MaiBot -> 完成时“进入 MaiDroid”，失败且没有任务在跑时“重试安装”，否则“请稍候”。
 * 两步失败时说明下面多一块：错误码、认出的原因和建议、跳转按钮和“反馈问题”。
 * 平板布局通过 modifier 限制按钮区最大宽度。
 */
@Composable
internal fun OobeStepFooter(
    step: OobeStep,
    setupState: OobeSetupState,
    notificationGranted: Boolean,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (step) {
        OobeStep.Permissions -> NextButton(
            text = if (notificationGranted) "下一步" else "请先授权",
            enabled = notificationGranted,
            onNext = onNext,
            modifier = modifier
        )

        OobeStep.Container -> SetupFooter(
            footerText = setupState.footerText,
            canProceed = setupState.canInstall,
            proceedText = "开始安装",
            waitingText = "重新配置",
            waitingEnabled = setupState.containerTasks.hasFailedTask(),
            failure = setupState.failure,
            onProceed = onNext,
            onRetry = onRetry,
            modifier = modifier
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
                failure = setupState.failure,
                onProceed = onNext,
                onRetry = onRetry,
                modifier = modifier
            )
        }
    }
}

/**
 * 打开或收起终端输出的液态玻璃胶囊按钮：
 * 平时为 44dp 圆形玻璃折射按钮实时透出下方插画；出错或等太久（attention）时弹性展开成“遇到问题？”，
 * 终端已展开（expanded）时渲染暖橙玻璃态并显示“收起终端”，按压带随指高光与果冻形变。
 */
@Composable
internal fun CommandLogButton(
    attention: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(OobeBackground)
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
    val animationScope = rememberCoroutineScope()
    val interactiveHighlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val pillShape = { Capsule() }
    val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.8.dp, GlassStroke, Capsule())

    Row(
        modifier = modifier
            .width(buttonWidth)
            .height(44.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = pillShape,
                effects = {
                    // 折射下方 OOBE 插画或深色工作区，展开态与圆钮态保持一致的液态光学质感
                    vibrancy()
                    blur(12f.dp.toPx())
                    lens(14f.dp.toPx(), 24f.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.9f) },
                shadow = { CommandLogButtonShadow },
                layerBlock = {
                    val transform = computeLiquidButtonDragTransform(
                        widthPx = size.width,
                        heightPx = size.height,
                        maxDragHeightPx = 3f.dp.toPx(),
                        pressProgress = interactiveHighlight.pressProgress,
                        offsetX = interactiveHighlight.offset.x,
                        offsetY = interactiveHighlight.offset.y
                    )
                    translationX = transform.translationXPx
                    translationY = transform.translationYPx
                    scaleX = transform.scaleX
                    scaleY = transform.scaleY
                },
                onDrawSurface = {
                    val baseSurface = if (hasRenderEffect) GlassButtonSurface else GlassFallbackSurface
                    when {
                        expanded -> {
                            drawRect(BrandOrange, blendMode = BlendMode.Hue)
                            drawRect(BrandOrange.copy(alpha = 0.28f))
                            drawRect(baseSurface)
                        }
                        attention -> {
                            drawRect(BrandOrange.copy(alpha = 0.22f))
                            drawRect(baseSurface)
                        }
                        else -> drawRect(baseSurface)
                    }
                }
            )
            .then(fallbackBorder)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(interactiveHighlight.modifier)
            .then(interactiveHighlight.gestureModifier)
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
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val CommandLogButtonShadow = Shadow(
    radius = 14.dp,
    color = GlassShadow
)

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
    LiquidButton(
        onClick = onNext,
        enabled = enabled,
        backdrop = rememberSurfaceBackdrop(OobeBackground),
        style = LiquidButtonStyle.Primary,
        shape = { RoundedRectangle(Corners.Chip) },
        minHeight = 64.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            fontSize = TextSizes.Headline,
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

// 配置容器、安装 MaiBot 两步的页脚：状态标志 + 当前进展说明，失败时再加错误码和建议，下面一个按钮。
// 能继续时按钮执行 onProceed，否则执行 onRetry（waitingEnabled 为 false 时按钮禁用）。
@Composable
private fun SetupFooter(
    footerText: String,
    canProceed: Boolean,
    proceedText: String,
    waitingText: String,
    waitingEnabled: Boolean,
    failure: Failure?,
    onProceed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corners.Card))
            .background(OobeSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧标志跟着按钮走：能继续时打勾；只能重试说明出错了，显示失败图标；其余时候转圈表示还在进行。
            when {
                canProceed -> FooterStatusIcon(R.drawable.oobe_status_done)
                waitingEnabled -> FooterStatusIcon(R.drawable.oobe_status_failed)
                else -> WaitingRing()
            }

            Spacer(modifier = Modifier.size(12.dp))

            Text(
                text = footerText,
                modifier = Modifier.weight(1f),
                fontSize = TextSizes.Body,
                fontWeight = FontWeight.Normal,
                color = OobeTextSecondary,
                letterSpacing = 0.sp,
                lineHeight = 18.sp
            )
        }

        if (failure != null && !canProceed) {
            // 故障详情可能含求助码或长建议：放在弹性可滚槽内，确保无论屏高多小都不会压扁底部重试按钮。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                FailureNotice(failure = failure, textColor = OobeTextSecondary)
            }
        }

        LiquidButton(
            onClick = if (canProceed) onProceed else onRetry,
            enabled = canProceed || waitingEnabled,
            backdrop = rememberSurfaceBackdrop(OobeSurface),
            style = LiquidButtonStyle.Primary,
            shape = { RoundedRectangle(Corners.Control) },
            minHeight = 44.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (canProceed) proceedText else waitingText,
                fontSize = TextSizes.BodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                letterSpacing = 0.sp,
                lineHeight = 20.sp
            )
        }
    }
}

// 页脚的完成、失败标志，与任务卡片右侧用同一套图标。
@Composable
private fun FooterStatusIcon(@DrawableRes iconRes: Int) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = Modifier.size(24.dp)
    )
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
