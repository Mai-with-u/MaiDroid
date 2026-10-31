package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import org.maiwithu.maidroid.ui.theme.BootAccent
import org.maiwithu.maidroid.ui.theme.BootInk
import org.maiwithu.maidroid.ui.theme.BootText
import org.maiwithu.maidroid.ui.theme.BootTextDim
import org.maiwithu.maidroid.ui.theme.BootTextFaint
import org.maiwithu.maidroid.ui.theme.BootTrack
import org.maiwithu.maidroid.ui.theme.JetBrainsMono
import kotlin.math.exp
import kotlin.math.roundToInt

private val BootEasing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

private val BootMetaStyle = TextStyle(
    fontFamily = JetBrainsMono,
    fontSize = 11.sp,
    letterSpacing = 0.12.em
)

// 自检行逐行出现的间隔；进度走满、LINK 行出现后停一下再收尾。
private const val BootLineStepMillis = 170L
private const val BootCompleteHoldMillis = 220L

// 等待中的光标闪烁周期（亮、灭各一次）的一半。
private const val BootCursorBlinkMillis = 530L

// 进度条追赶目标值：差得多时按时间常数（秒）指数逼近，差得少时至少按最低速度（每秒走的比例）前进，
// 末尾几个百分点干脆利落地走完，不在 97%–99% 上磨蹭。
private const val BootProgressSmoothingSeconds = 0.12f
private const val BootProgressMinSpeed = 0.8f

// 收尾时间轴（毫秒）：0–300 文字淡出 -> 200–480 中缝从中间向两侧划开 ->
// 500 起中缝交给两扇快门的边线，快门用 800 上下拉开，露出底下已经就位的主页。
private const val ExitTextFadeMillis = 300f
private const val ExitSeamStartMillis = 200f
private const val ExitSeamMillis = 280f
private const val ExitShutterStartMillis = 500f
private const val ExitShutterMillis = 800f
private const val ExitTotalMillis = 1_300

/**
 * 主页的“唤醒麦麦”页：OOBE 完成后、WebUI 还没起来时盖在主页上面，样式是终端开机自检画面。
 *
 * 抬头 -> 容器 ->（MaiDroid 更新后首次启动时）MaiBot 自动升级 -> MaiBot 进程 -> WebUI 自检行按真实状态逐行打出
 * （后一行要等前一行有结论）-> 都通过后打出 LINK 行，
 * 进度条走满 -> 停一下调 onFinished，让主页在底下就位 -> leaving 变 true 后播收尾：文字淡出、中缝划开、快门上下拉开 ->
 * 拉开后调 onLeft，由主页把这一层移除。点 SKIP 也调 onFinished，同样走收尾这一段。
 * 自动唤醒（每 WakeRetryIntervalMillis 一次）由 HomeScreen 负责，这里只显示进度。
 * maiBotUpdate 只在这次启动要自动升级 MaiBot 时传入（整页存续期间不变），据此多显示一行升级进度；
 * 升级期间麦麦被拦着不启动，自动唤醒的请求会被忽略，升级完由更新管理器拉起。
 */
@Composable
internal fun WakeMaiPage(
    webUiOnline: Boolean,
    maiBotStatus: MaiBotProcessStatus,
    versionName: String,
    maiBotUpdate: MaiBotUpdateState?,
    leaving: Boolean,
    onFinished: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 只算唤醒页出现之后的意外退出，之前崩过几次和这次唤醒无关。
    val baselineExits = remember { maiBotStatus.exitCount }
    val lines = bootLines(
        webUiOnline,
        maiBotStatus,
        exits = maiBotStatus.exitCount - baselineExits,
        update = maiBotUpdate?.let(::maiBotUpdateBootLine)
    )
    val currentLines by rememberUpdatedState(lines)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnLeft by rememberUpdatedState(onLeft)

    var revealed by remember { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var cursorVisible by remember { mutableStateOf(true) }
    val exitClock = remember { Animatable(0f) }

    // 逐行打出：上一行还没结论（MaiBot 还在升级、还在起、WebUI 还没上线）就停在这里等，有结论后隔一拍再打出下一行。
    LaunchedEffect(Unit) {
        while (revealed <= currentLines.size) {
            if (revealed > 0) {
                snapshotFlow { currentLines[revealed - 1].check.concluded }.first { it }
            }
            delay(BootLineStepMillis)
            revealed++
        }
    }

    // 进度条每帧朝当前阶段的目标值平滑追赶，只进不退，也不越过目标值。
    LaunchedEffect(Unit) {
        var lastFrame = withFrameNanos { it }
        var stageRevealed = revealed
        var stageStart = lastFrame
        while (true) {
            withFrameNanos { now ->
                if (revealed != stageRevealed) {
                    stageRevealed = revealed
                    stageStart = now
                }
                val target = bootProgressTarget(revealed, currentLines, waitingSeconds = (now - stageStart) / 1e9f)
                val elapsed = (now - lastFrame) / 1e9f
                lastFrame = now
                if (target != null && target > progress) {
                    val step = maxOf(
                        (target - progress) * (1f - exp(-elapsed / BootProgressSmoothingSeconds)),
                        BootProgressMinSpeed * elapsed
                    )
                    progress = minOf(target, progress + step)
                }
            }
        }
    }

    // LINK 行出来、进度走满 -> 停一下 -> 通知主页切过去。
    LaunchedEffect(Unit) {
        snapshotFlow { revealed > currentLines.size && progress >= 1f }.first { it }
        delay(BootCompleteHoldMillis)
        currentOnFinished()
    }

    // 还在等的阶段，状态词后面的光标按终端的节奏闪烁。
    LaunchedEffect(Unit) {
        while (true) {
            delay(BootCursorBlinkMillis)
            cursorVisible = !cursorVisible
        }
    }

    LaunchedEffect(leaving) {
        if (!leaving) return@LaunchedEffect
        exitClock.animateTo(ExitTotalMillis.toFloat(), tween(ExitTotalMillis, easing = LinearEasing))
        currentOnLeft()
    }

    val percent by remember { derivedStateOf { (progress * 100).roundToInt() } }
    val stalled = lines.any { it.check == BootCheck.Warning || it.check == BootCheck.Failed }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 只占住触摸、不消费：收尾时主页已经在底下，快门拉开之前不让点击漏到下面的网页上。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent()
                }
            }
    ) {
        val bodyWidth = minOf(maxWidth * 0.86f, 480.dp)

        BootShutters(
            exitMillis = { exitClock.value },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(bodyWidth)
                .graphicsLayer { alpha = 1f - (exitClock.value / ExitTextFadeMillis).coerceIn(0f, 1f) }
        ) {
            BootLog(
                header = "MAIDROID v$versionName // BOOT",
                lines = lines,
                revealed = revealed,
                cursorVisible = cursorVisible,
                blockedReason = maiBotStatus.launchBlockedReason,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(22.dp))

            BootProgressBar(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            )

            BootMeta(
                percent = percent,
                // 卡住或在反复重启时把 SKIP 提亮一档，提示可以先进主页看终端输出和设置。
                emphasizeSkip = stalled,
                onSkip = onFinished
            )
        }
    }
}

// 4dp 细进度条：深灰轨道上画一段强调色。进度在绘制阶段读取，走动时只重画、不重组。
@Composable
private fun BootProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(color = BootTrack)
        drawRect(
            color = BootAccent,
            size = Size(size.width * progress().coerceIn(0f, 1f), size.height)
        )
    }
}

// 进度条下面一行：左边三位数百分比，右边 SKIP。SKIP 上下留出点按区域，所以这一行不再额外留间距。
@Composable
private fun BootMeta(
    percent: Int,
    emphasizeSkip: Boolean,
    onSkip: () -> Unit
) {
    val skipInteraction = remember { MutableInteractionSource() }
    val skipPressed by skipInteraction.collectIsPressedAsState()
    val skipColor = when {
        skipPressed -> BootText
        emphasizeSkip -> BootTextDim
        else -> BootTextFaint
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "${percent.coerceIn(0, 100).toString().padStart(3, '0')}%",
            style = BootMetaStyle,
            color = BootTextFaint,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "SKIP ⏎",
            style = BootMetaStyle,
            color = skipColor,
            modifier = Modifier
                .clickable(
                    interactionSource = skipInteraction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "跳过，先进入主页",
                    onClick = onSkip
                )
                .padding(start = 24.dp, top = 10.dp, bottom = 10.dp)
        )
    }
}

// 底色就是两扇快门：上扇占 50.5%，下扇从 49.5% 起，中间叠 1% 免得拉开前露缝。
// 收尾时中缝（与下扇上沿重合）先从中间向两侧划开，到 500ms 交给两扇快门各自的边线，快门再带着边线上下拉开。
@Composable
private fun BootShutters(exitMillis: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val t = exitMillis()
        val line = 1.dp.toPx()
        val shutterHeight = size.height * 0.505f
        val open = BootEasing.transform(((t - ExitShutterStartMillis) / ExitShutterMillis).coerceIn(0f, 1f))
        val travel = shutterHeight * 1.01f * open

        val topBottom = shutterHeight - travel
        drawRect(color = BootInk, topLeft = Offset(0f, -travel), size = Size(size.width, shutterHeight))
        val bottomTop = size.height - shutterHeight + travel
        drawRect(color = BootInk, topLeft = Offset(0f, bottomTop), size = Size(size.width, shutterHeight))

        if (t >= ExitShutterStartMillis) {
            drawRect(color = BootAccent, topLeft = Offset(0f, topBottom - line), size = Size(size.width, line))
            drawRect(color = BootAccent, topLeft = Offset(0f, bottomTop), size = Size(size.width, line))
        } else if (t > ExitSeamStartMillis) {
            val seam = BootEasing.transform(((t - ExitSeamStartMillis) / ExitSeamMillis).coerceIn(0f, 1f))
            val seamWidth = size.width * seam
            drawRect(
                color = BootAccent,
                topLeft = Offset((size.width - seamWidth) / 2f, size.height - shutterHeight),
                size = Size(seamWidth, line)
            )
        }
    }
}
