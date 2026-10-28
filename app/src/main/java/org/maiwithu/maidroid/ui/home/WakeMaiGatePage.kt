package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.DesignFrameHeightDp
import org.maiwithu.maidroid.ui.theme.DesignFrameWidthDp

// 唤醒页专用的冷色科技风配色（其余页面不用）。
private val HomeBackground = Color(0xFF07090D)
private val HomeOrange = Color(0xFFE8921E)
private val HomeTextBlue = Color(0xFFA0B4C8)
private val HomeMutedBlue = Color(0xFF788CA0)
private val HomeOnline = Color(0xFF64C88C)
private val HomeOffline = Color(0xFFE8A064)

/**
 * 设计稿坐标到实际尺寸的换算：横向按宽度比例、纵向按高度比例，文字和描边按两者中较小的比例，
 * 这样不同长宽比的屏幕上插画不变形、文字不溢出。
 */
private class DesignScale(val scaleX: Float, val scaleY: Float) {
    val textScale: Float = minOf(scaleX, scaleY)
    fun x(value: Float): Dp = (value * scaleX).dp
    fun y(value: Float): Dp = (value * scaleY).dp
    fun sp(value: Float): TextUnit = (value * textScale).sp
}

/**
 * 主页的“唤醒麦麦”页：OOBE 完成后、WebUI 还没起来时显示。
 * 每 30 秒（WakeRetryIntervalMillis）自动唤醒一次 MaiBot，进度条在这段时间内从左走到右；WebUI 一上线就切到主页。
 */
@Composable
internal fun WakeMaiGatePage(
    webUiOnline: Boolean,
    versionName: String,
    wakeCycle: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(HomeBackground)
    ) {
        val scale = DesignScale(
            scaleX = maxWidth.value / DesignFrameWidthDp,
            scaleY = maxHeight.value / DesignFrameHeightDp
        )
        val textScale = scale.textScale
        val wakeProgress = remember { Animatable(if (webUiOnline) 1f else 0f) }

        LaunchedEffect(wakeCycle, webUiOnline) {
            if (webUiOnline) {
                wakeProgress.snapTo(1f)
            } else {
                wakeProgress.snapTo(0f)
                wakeProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = WakeRetryIntervalMillis.toInt(),
                        easing = LinearEasing
                    )
                )
            }
        }

        Image(
            painter = painterResource(R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset(x = scale.x(0f), y = scale.y(-4f))
                .width(scale.x(600f))
                .height(scale.y(789f))
                .graphicsLayer { alpha = 0.5f }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(scale.y(156.75f))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            HomeBackground.copy(alpha = 0.85f),
                            HomeBackground.copy(alpha = 0f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(scale.y(384.04f))
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to HomeBackground.copy(alpha = 0f),
                            0.3f to HomeBackground.copy(alpha = 0.8f),
                            0.6f to HomeBackground.copy(alpha = 0.97f),
                            1f to HomeBackground
                        )
                    )
                )
        )

        HudOverlay(modifier = Modifier.fillMaxSize())

        HeaderLabels(
            webUiOnline = webUiOnline,
            versionName = versionName,
            scale = scale
        )

        BootTelemetry(
            scale = scale
        )

        Text(
            text = "麦麦Bot",
            color = Color.White,
            fontSize = scale.sp(48f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = scale.sp(4f),
            lineHeight = scale.sp(52f),
            modifier = Modifier
                .offset(x = scale.x(40f), y = scale.y(588f))
                .width(scale.x(220f))
        )

        Text(
            text = "MaiSaka × MaiDroid",
            color = HomeOrange.copy(alpha = 0.85f),
            fontSize = scale.sp(16f),
            fontWeight = FontWeight.Bold,
            letterSpacing = scale.sp(6f),
            lineHeight = scale.sp(20f),
            modifier = Modifier
                .offset(x = scale.x(39f), y = scale.y(643f))
                .width(scale.x(315f))
        )

        Box(
            modifier = Modifier
                .offset(x = scale.x(39f), y = scale.y(667f))
                .width(scale.x(303f))
                .height(scale.y(3f))
                .background(HomeOrange.copy(alpha = 0.25f))
        )

        Text(
            text = "一个正在努力成为人类的BOT。",
            color = Color(0xFFB4C3D7).copy(alpha = 0.7f),
            fontSize = scale.sp(12f),
            fontWeight = FontWeight.Bold,
            letterSpacing = scale.sp(1f),
            lineHeight = scale.sp(16f),
            modifier = Modifier
                .offset(x = scale.x(40f), y = scale.y(679f))
                .width(scale.x(240f))
        )

        WakeProgressBar(
            progress = wakeProgress.value,
            webUiOnline = webUiOnline,
            modifier = Modifier
                .offset(x = scale.x(38f), y = scale.y(706f))
                .width(scale.x(335.7f))
                .height(scale.y(59.6f)),
            textScale = textScale
        )

        Text(
            text = if (webUiOnline) {
                "MAIBOT READY  //  ENTERING WEBUI"
            } else {
                "WEBUI OFFLINE  //  AUTO WAKE"
            },
            color = Color(0xFF96A5B9).copy(alpha = 0.4f),
            fontSize = scale.sp(7.05f),
            letterSpacing = scale.sp(2f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = scale.y(118f))
                .width(scale.x(335.7f))
        )
    }
}
@Composable
private fun HeaderLabels(
    webUiOnline: Boolean,
    versionName: String,
    scale: DesignScale
) {

    Text(
        text = "MAISAKA",
        color = HomeOrange.copy(alpha = 0.9f),
        fontSize = scale.sp(8.62f),
        letterSpacing = scale.sp(4f),
        modifier = Modifier
            .offset(x = scale.x(31f), y = scale.y(44f))
            .width(scale.x(90f))
    )

    Text(
        text = "牢麦待机中",
        color = HomeTextBlue.copy(alpha = 0.7f),
        fontSize = scale.sp(7.05f),
        letterSpacing = scale.sp(1.5f),
        modifier = Modifier
            .offset(x = scale.x(31f), y = scale.y(58f))
            .width(scale.x(96f))
    )

    Text(
        text = "v$versionName",
        color = HomeMutedBlue.copy(alpha = 0.6f),
        fontSize = scale.sp(7.84f),
        textAlign = TextAlign.End,
        modifier = Modifier
            .offset(x = scale.x(320f), y = scale.y(45f))
            .width(scale.x(58f))
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .offset(x = scale.x(300f), y = scale.y(56f))
            .width(scale.x(78f))
    ) {
        Box(
            modifier = Modifier
                .size(scale.x(3.9f))
                .clip(CircleShape)
                .background(if (webUiOnline) HomeOnline else HomeOffline)
        )
        Spacer(modifier = Modifier.width(scale.x(4f)))
        Text(
            text = if (webUiOnline) "ONLINE" else "OFFLINE",
            color = if (webUiOnline) HomeOnline.copy(alpha = 0.8f) else HomeOffline.copy(alpha = 0.82f),
            fontSize = scale.sp(7.05f),
            letterSpacing = scale.sp(2f)
        )
    }
}

@Composable
private fun BootTelemetry(
    scale: DesignScale
) {

    Text(
        text = "114°51'N  41°91'E",
        color = Color(0xFF788CAA).copy(alpha = 0.4f),
        fontSize = scale.sp(6.27f),
        letterSpacing = scale.sp(1f),
        modifier = Modifier
            .offset(x = scale.x(16.8f), y = scale.y(352.7f))
            .width(scale.x(124f))
    )

    Text(
        text = "MAI.BOOT  //  AUTO WAKE",
        color = Color(0xFFC8A064).copy(alpha = 0.35f),
        fontSize = scale.sp(6.27f),
        letterSpacing = scale.sp(1.5f),
        modifier = Modifier
            .offset(x = scale.x(16.8f), y = scale.y(362.1f))
            .width(scale.x(180f))
    )
}

@Composable
private fun WakeProgressBar(
    progress: Float,
    webUiOnline: Boolean,
    modifier: Modifier,
    textScale: Float
) {
    val clampedProgress = progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .shadow(
                elevation = (7f * textScale).dp,
                shape = WakeButtonShape,
                ambientColor = HomeOrange.copy(alpha = 0.2f),
                spotColor = HomeOrange.copy(alpha = 0.34f)
            )
            .clip(WakeButtonShape)
            .background(Color(0xFF151A22))
            .drawWithContent {
                drawContent()
                drawLine(
                    color = Color.White.copy(alpha = 0.18f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = Color.Black.copy(alpha = 0.22f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width - 18.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx()
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(clampedProgress)
                .background(HomeOrange)
        )

        Text(
            text = if (webUiOnline) "麦麦已唤醒" else "正在唤醒麦麦",
            color = Color.White,
            fontSize = (20f * textScale).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (2f * textScale).sp,
            lineHeight = (24f * textScale).sp
        )
    }
}

private val WakeButtonShape = GenericShape { size, _ ->
    val radius = size.height * 0.1f
    val cut = size.height * 0.3f
    moveTo(radius, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height - cut)
    lineTo(size.width - cut, size.height)
    lineTo(radius, size.height)
    quadraticTo(0f, size.height, 0f, size.height - radius)
    lineTo(0f, radius)
    quadraticTo(0f, 0f, radius, 0f)
    close()
}

@Composable
private fun HudOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val sx = size.width / DesignFrameWidthDp
        val sy = size.height / DesignFrameHeightDp
        val scale = minOf(sx, sy)
        fun x(value: Float) = value * sx
        fun y(value: Float) = value * sy
        fun stroke(value: Float) = (value * scale).coerceAtLeast(1f)

        fun line(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            alpha: Float,
            color: Color = HomeOrange,
            width: Float = 0.78f
        ) {
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(x(startX), y(startY)),
                end = Offset(x(endX), y(endY)),
                strokeWidth = stroke(width)
            )
        }

        fun diamond(
            left: Float,
            top: Float,
            side: Float,
            alpha: Float,
            fillAlpha: Float = 0f,
            width: Float = 1f
        ) {
            val cx = x(left + side / 2f)
            val cy = y(top + side / 2f)
            val half = side * 0.36f * scale
            val path = Path().apply {
                moveTo(cx, cy - half)
                lineTo(cx + half, cy)
                lineTo(cx, cy + half)
                lineTo(cx - half, cy)
                close()
            }
            if (fillAlpha > 0f) {
                drawPath(path = path, color = HomeOrange.copy(alpha = fillAlpha))
            }
            drawPath(
                path = path,
                color = HomeOrange.copy(alpha = alpha),
                style = Stroke(width = stroke(width))
            )
        }

        line(16.8f, 17.24f, 35.1f, 17.24f, 0.6f)
        line(16.8f, 17.24f, 16.8f, 36.05f, 0.6f)
        line(376.9f, 17.24f, 395.2f, 17.24f, 0.6f)
        line(394.45f, 17.24f, 394.45f, 36.05f, 0.6f)
        line(31f, 72f, 381.96f, 72f, 0.3f)

        line(16.8f, 109.7f, 16.8f, 344.9f, 0.2f)
        line(394.45f, 125.4f, 394.45f, 344.9f, 0.15f, Color(0xFFC8C8DC))
        listOf(313.5f, 344.85f, 407.56f, 438.91f).forEach { y ->
            line(0f, y, 412f, y, 0.04f, Color(0xFFB4C8DC))
        }

        line(17f, 899f, 38.36f, 899f, 0.45f)
        line(17f, 877f, 17f, 899f, 0.45f)
        line(374f, 899f, 395.36f, 899f, 0.45f)
        line(394.6f, 877f, 394.6f, 899f, 0.45f)

        diamond(152.6f, 89.7f, 8.75f, 0.5f, width = 1.5f)
        diamond(42f, 243.25f, 15.31f, 0.55f, fillAlpha = 0.1f, width = 1.5f)
        diamond(350.96f, 152.98f, 7.66f, 0.35f)
        diamond(22.89f, 372.97f, 6.56f, 0.3f)
        diamond(381.48f, 347.3f, 10.94f, 0.4f)
        diamond(206f, 83.52f, 5.47f, 0.25f)
        diamond(61.04f, 465.94f, 8.75f, 0.3f)
        diamond(19.07f, 574.76f, 19.69f, 0.9f, fillAlpha = 0.25f, width = 2f)
        diamond(383.01f, 26.74f, 6.56f, 0.5f, fillAlpha = 0.5f, width = 0f)
    }
}
