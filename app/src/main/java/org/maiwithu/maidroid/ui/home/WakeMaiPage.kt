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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
import org.maiwithu.maidroid.ui.theme.TextPrimary

// 唤醒页专用的冷色科技风配色（其余页面不用）。
private val WakeBackground = Color(0xFF07090D)
private val WakeOrange = Color(0xFFE8921E)
private val WakeTextBlue = Color(0xFFA0B4C8)
private val WakeMutedBlue = Color(0xFF788CA0)
private val WakeOnline = Color(0xFF64C88C)
private val WakeOffline = Color(0xFFE8A064)

// 横屏版式的参照画幅：竖屏设计稿转 90°，横屏时所有元素按它统一缩放，不再横竖分开拉伸。
private const val LandscapeFrameWidthDp = 917f
private const val LandscapeFrameHeightDp = 412f

// 插画原图的宽高比（home_background.png 是 623×800 的竖幅，边缘带故障风透明锯齿）。
private const val WakeArtAspectRatio = 623f / 800f

/**
 * 设计稿坐标到实际尺寸的换算：横向按 scaleX、纵向按 scaleY，文字和描边按两者中较小的比例。
 * x/y 换算尺寸（宽、高），px/py 换算位置：位置再加上 origin，横屏时用来把一组元素整体挪到屏幕的某个角。
 * 竖屏 origin 为 0，和原来的绝对坐标完全一样。
 */
private class DesignScale(
    val scaleX: Float,
    val scaleY: Float,
    val originX: Dp = 0.dp,
    val originY: Dp = 0.dp
) {
    val textScale: Float = minOf(scaleX, scaleY)
    fun x(value: Float): Dp = (value * scaleX).dp
    fun y(value: Float): Dp = (value * scaleY).dp
    fun px(value: Float): Dp = originX + x(value)
    fun py(value: Float): Dp = originY + y(value)
    fun sp(value: Float): TextUnit = (value * textScale).sp
}

/**
 * 唤醒页各组元素的换算方式。竖屏四组都是同一个按设计稿拉伸的换算；
 * 横屏时统一按 scale 缩放，再分别挪到左上（标题标签）、右上（版本与状态）、右下（遥测字样）、左下（标题与进度条）。
 */
private class WakeLayout(
    val landscape: Boolean,
    val scale: Float,
    val headerLeft: DesignScale,
    val headerRight: DesignScale,
    val telemetry: DesignScale,
    val titleBlock: DesignScale
)

/**
 * 竖屏：沿用设计稿的绝对坐标，横纵分别按屏幕宽高比例拉伸。
 * 横屏：按 917×412 的横向画幅统一缩放；左右各让出挖孔安全区（横屏时挖孔转到侧边）。
 * 标题区设计稿里占 x 38~374、y 588~782，横屏时整块放到左下角，底边离屏幕底 28 个设计单位。
 */
private fun wakeLayout(width: Dp, height: Dp, safeLeft: Dp, safeRight: Dp): WakeLayout {
    if (width <= height) {
        val design = DesignScale(
            scaleX = width.value / DesignFrameWidthDp,
            scaleY = height.value / DesignFrameHeightDp
        )
        return WakeLayout(false, design.textScale, design, design, design, design)
    }

    val s = minOf(width.value / LandscapeFrameWidthDp, height.value / LandscapeFrameHeightDp)
    fun scaled(originX: Dp, originY: Dp) = DesignScale(s, s, originX, originY)
    val right = width - safeRight
    return WakeLayout(
        landscape = true,
        scale = s,
        headerLeft = scaled(safeLeft, 0.dp),
        // 设计稿右侧标签的右边缘在 x=378（离右边 34），横屏时贴着安全区右边缘同样留 34。
        headerRight = scaled(right - (DesignFrameWidthDp * s).dp, 0.dp),
        // 遥测字样（设计稿 x=16.8、y=352.7）挪到右下角，压在插画上，离右边和底边各 34、52。
        telemetry = scaled(right - ((34f + 180f + 16.8f) * s).dp, height - ((52f + 352.7f) * s).dp),
        titleBlock = scaled(safeLeft + (10f * s).dp, height - ((28f + 782f) * s).dp)
    )
}

/**
 * 主页的“唤醒麦麦”页：OOBE 完成后、WebUI 还没起来时显示。
 * 每 30 秒（WakeRetryIntervalMillis）自动唤醒一次 MaiBot，进度条在这段时间内从左走到右；WebUI 一上线就切到主页。
 * 竖屏按设计稿铺满；横屏时插画竖着放在右侧，标题和进度条在左下，元素等比缩放、不横向拉伸。
 */
@Composable
internal fun WakeMaiPage(
    webUiOnline: Boolean,
    versionName: String,
    wakeCycle: Int,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(WakeBackground)
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val safeLeft = with(density) { WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp() }
        val safeRight = with(density) { WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp() }
        val layout = wakeLayout(maxWidth, maxHeight, safeLeft, safeRight)
        val portrait = layout.titleBlock
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

        if (layout.landscape) {
            // 横屏：整幅插画竖着贴右侧，透明锯齿边缘直接融进深色背景。
            Image(
                painter = painterResource(R.drawable.home_background),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = safeRight + (24f * layout.scale).dp)
                    .fillMaxHeight()
                    .aspectRatio(WakeArtAspectRatio)
                    .graphicsLayer { alpha = 0.5f }
            )
        } else {
            Image(
                painter = painterResource(R.drawable.home_background),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset(x = portrait.x(0f), y = portrait.y(-4f))
                    .width(portrait.x(600f))
                    .height(portrait.y(789f))
                    .graphicsLayer { alpha = 0.5f }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (layout.landscape) (156.75f * layout.scale).dp else portrait.y(156.75f))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            WakeBackground.copy(alpha = 0.85f),
                            WakeBackground.copy(alpha = 0f)
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (layout.landscape) maxHeight * 0.55f else portrait.y(384.04f))
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to WakeBackground.copy(alpha = 0f),
                            0.3f to WakeBackground.copy(alpha = 0.8f),
                            0.6f to WakeBackground.copy(alpha = 0.97f),
                            1f to WakeBackground
                        )
                    )
                )
        )

        if (layout.landscape) {
            LandscapeHudOverlay(
                scale = layout.scale,
                safeLeft = safeLeft,
                safeRight = safeRight,
                titleTop = layout.titleBlock.py(588f),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            HudOverlay(modifier = Modifier.fillMaxSize())
        }

        HeaderLabels(
            webUiOnline = webUiOnline,
            versionName = versionName,
            leftScale = layout.headerLeft,
            rightScale = layout.headerRight
        )

        BootTelemetry(scale = layout.telemetry)

        WakeTitleBlock(
            webUiOnline = webUiOnline,
            progress = wakeProgress.value,
            scale = layout.titleBlock
        )

        val footerText = if (webUiOnline) {
            "MAIBOT READY  //  ENTERING WEBUI"
        } else {
            "WEBUI OFFLINE  //  AUTO WAKE"
        }
        val footerColor = Color(0xFF96A5B9).copy(alpha = 0.4f)
        if (layout.landscape) {
            // 横屏放在进度条正下方，和进度条同宽居中。
            val title = layout.titleBlock
            Text(
                text = footerText,
                color = footerColor,
                fontSize = title.sp(7.05f),
                letterSpacing = title.sp(2f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .offset(x = title.px(38f), y = title.py(772f))
                    .width(title.x(335.7f))
            )
        } else {
            Text(
                text = footerText,
                color = footerColor,
                fontSize = portrait.sp(7.05f),
                letterSpacing = portrait.sp(2f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = portrait.y(118f))
                    .width(portrait.x(335.7f))
            )
        }
    }
}

/** 左下的标题区：“麦麦Bot”、副标题、分隔线、标语和唤醒进度条，坐标都是设计稿里的位置。 */
@Composable
private fun WakeTitleBlock(
    webUiOnline: Boolean,
    progress: Float,
    scale: DesignScale
) {
    Text(
        text = "麦麦Bot",
        color = TextPrimary,
        fontSize = scale.sp(48f),
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = scale.sp(4f),
        lineHeight = scale.sp(52f),
        modifier = Modifier
            .offset(x = scale.px(40f), y = scale.py(588f))
            .width(scale.x(220f))
    )

    Text(
        text = "MaiSaka × MaiDroid",
        color = WakeOrange.copy(alpha = 0.85f),
        fontSize = scale.sp(16f),
        fontWeight = FontWeight.Bold,
        letterSpacing = scale.sp(6f),
        lineHeight = scale.sp(20f),
        modifier = Modifier
            .offset(x = scale.px(39f), y = scale.py(643f))
            .width(scale.x(315f))
    )

    Box(
        modifier = Modifier
            .offset(x = scale.px(39f), y = scale.py(667f))
            .width(scale.x(303f))
            .height(scale.y(3f))
            .background(WakeOrange.copy(alpha = 0.25f))
    )

    Text(
        text = "一个正在努力成为人类的BOT。",
        color = Color(0xFFB4C3D7).copy(alpha = 0.7f),
        fontSize = scale.sp(12f),
        fontWeight = FontWeight.Bold,
        letterSpacing = scale.sp(1f),
        lineHeight = scale.sp(16f),
        modifier = Modifier
            .offset(x = scale.px(40f), y = scale.py(679f))
            .width(scale.x(240f))
    )

    WakeProgressBar(
        progress = progress,
        webUiOnline = webUiOnline,
        modifier = Modifier
            .offset(x = scale.px(38f), y = scale.py(706f))
            .width(scale.x(335.7f))
            .height(scale.y(59.6f)),
        textScale = scale.textScale
    )
}

@Composable
private fun HeaderLabels(
    webUiOnline: Boolean,
    versionName: String,
    leftScale: DesignScale,
    rightScale: DesignScale
) {

    Text(
        text = "MAISAKA",
        color = WakeOrange.copy(alpha = 0.9f),
        fontSize = leftScale.sp(8.62f),
        letterSpacing = leftScale.sp(4f),
        modifier = Modifier
            .offset(x = leftScale.px(31f), y = leftScale.py(44f))
            .width(leftScale.x(90f))
    )

    Text(
        text = "牢麦待机中",
        color = WakeTextBlue.copy(alpha = 0.7f),
        fontSize = leftScale.sp(7.05f),
        letterSpacing = leftScale.sp(1.5f),
        modifier = Modifier
            .offset(x = leftScale.px(31f), y = leftScale.py(58f))
            .width(leftScale.x(96f))
    )

    Text(
        text = "v$versionName",
        color = WakeMutedBlue.copy(alpha = 0.6f),
        fontSize = rightScale.sp(7.84f),
        textAlign = TextAlign.End,
        modifier = Modifier
            .offset(x = rightScale.px(320f), y = rightScale.py(45f))
            .width(rightScale.x(58f))
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .offset(x = rightScale.px(300f), y = rightScale.py(56f))
            .width(rightScale.x(78f))
    ) {
        Box(
            modifier = Modifier
                .size(rightScale.x(3.9f))
                .clip(CircleShape)
                .background(if (webUiOnline) WakeOnline else WakeOffline)
        )
        Spacer(modifier = Modifier.width(rightScale.x(4f)))
        Text(
            text = if (webUiOnline) "ONLINE" else "OFFLINE",
            color = if (webUiOnline) WakeOnline.copy(alpha = 0.8f) else WakeOffline.copy(alpha = 0.82f),
            fontSize = rightScale.sp(7.05f),
            letterSpacing = rightScale.sp(2f)
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
            .offset(x = scale.px(16.8f), y = scale.py(352.7f))
            .width(scale.x(124f))
    )

    Text(
        text = "MAI.BOOT  //  AUTO WAKE",
        color = Color(0xFFC8A064).copy(alpha = 0.35f),
        fontSize = scale.sp(6.27f),
        letterSpacing = scale.sp(1.5f),
        modifier = Modifier
            .offset(x = scale.px(16.8f), y = scale.py(362.1f))
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
                ambientColor = WakeOrange.copy(alpha = 0.2f),
                spotColor = WakeOrange.copy(alpha = 0.34f)
            )
            .clip(WakeButtonShape)
            .background(Color(0xFF151A22))
            .drawWithContent {
                drawContent()
                drawLine(
                    color = TextPrimary.copy(alpha = 0.18f),
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
                .background(WakeOrange)
        )

        Text(
            text = if (webUiOnline) "麦麦已唤醒" else "正在唤醒麦麦",
            color = TextPrimary,
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

/** 菱形装饰：中心 (centerX, centerY)、半条对角线 halfDiagonal 都是像素；fillAlpha 大于 0 时先填色再描边。 */
private fun DrawScope.hudDiamond(
    centerX: Float,
    centerY: Float,
    halfDiagonal: Float,
    alpha: Float,
    strokeWidth: Float,
    fillAlpha: Float = 0f
) {
    val path = Path().apply {
        moveTo(centerX, centerY - halfDiagonal)
        lineTo(centerX + halfDiagonal, centerY)
        lineTo(centerX, centerY + halfDiagonal)
        lineTo(centerX - halfDiagonal, centerY)
        close()
    }
    if (fillAlpha > 0f) {
        drawPath(path = path, color = WakeOrange.copy(alpha = fillAlpha))
    }
    drawPath(
        path = path,
        color = WakeOrange.copy(alpha = alpha),
        style = Stroke(width = strokeWidth)
    )
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
            color: Color = WakeOrange,
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
            hudDiamond(
                centerX = x(left + side / 2f),
                centerY = y(top + side / 2f),
                halfDiagonal = side * 0.36f * scale,
                alpha = alpha,
                strokeWidth = stroke(width),
                fillAlpha = fillAlpha
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

/**
 * 横屏的 HUD 线框：和竖屏同一套元素（四角折线、标题下的横线、两侧竖线、淡横纹、菱形），
 * 但长度和粗细统一按 scale，位置贴着安全区内的四边和四角，不随屏幕宽高比拉长或压扁。
 * 最显眼的实心菱形放在左下标题“麦麦Bot”的左上方，对应竖屏里它和标题的相对位置。
 */
@Composable
private fun LandscapeHudOverlay(
    scale: Float,
    safeLeft: Dp,
    safeRight: Dp,
    titleTop: Dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val unit = scale * density
        val left = safeLeft.toPx()
        val right = size.width - safeRight.toPx()
        val bottom = size.height
        val titleTopPx = titleTop.toPx()
        fun u(value: Float) = value * unit
        fun stroke(value: Float) = (value * unit).coerceAtLeast(1f)

        fun line(start: Offset, end: Offset, alpha: Float, color: Color = WakeOrange, width: Float = 0.78f) {
            drawLine(
                color = color.copy(alpha = alpha),
                start = start,
                end = end,
                strokeWidth = stroke(width)
            )
        }

        fun diamond(centerX: Float, centerY: Float, side: Float, alpha: Float, fillAlpha: Float = 0f, width: Float = 1f) {
            hudDiamond(
                centerX = centerX,
                centerY = centerY,
                halfDiagonal = u(side * 0.36f),
                alpha = alpha,
                strokeWidth = stroke(width),
                fillAlpha = fillAlpha
            )
        }

        // 四角折线
        val inset = u(16.8f)
        val top = u(17.24f)
        val arm = u(18.3f)
        line(Offset(left + inset, top), Offset(left + inset + arm, top), 0.6f)
        line(Offset(left + inset, top), Offset(left + inset, top + arm), 0.6f)
        line(Offset(right - inset - arm, top), Offset(right - inset, top), 0.6f)
        line(Offset(right - inset, top), Offset(right - inset, top + arm), 0.6f)
        val cornerBottom = bottom - u(18f)
        line(Offset(left + inset, cornerBottom), Offset(left + inset + arm, cornerBottom), 0.45f)
        line(Offset(left + inset, cornerBottom - arm), Offset(left + inset, cornerBottom), 0.45f)
        line(Offset(right - inset - arm, cornerBottom), Offset(right - inset, cornerBottom), 0.45f)
        line(Offset(right - inset, cornerBottom - arm), Offset(right - inset, cornerBottom), 0.45f)

        // 标题标签下的横线、两侧竖线、几道很淡的横纹
        line(Offset(left + u(31f), u(72f)), Offset(right - u(31f), u(72f)), 0.3f)
        line(Offset(left + inset, u(110f)), Offset(left + inset, titleTopPx - u(40f)), 0.2f)
        line(Offset(right - inset, u(125f)), Offset(right - inset, bottom - u(110f)), 0.15f, Color(0xFFC8C8DC))
        listOf(0.34f, 0.38f, 0.45f, 0.49f).forEach { fraction ->
            val y = size.height * fraction
            line(Offset(0f, y), Offset(size.width, y), 0.04f, Color(0xFFB4C8DC))
        }

        // 菱形
        diamond(left + u(157f), u(94f), 8.75f, 0.5f, width = 1.5f)
        diamond(left + u(208.7f), u(86.3f), 5.47f, 0.25f)
        diamond(right - u(57f), u(157f), 7.66f, 0.35f)
        diamond(left + u(49.6f), u(150f), 15.31f, 0.55f, fillAlpha = 0.1f, width = 1.5f)
        diamond(right - u(25f), size.height * 0.42f, 10.94f, 0.4f)
        diamond(right - u(25.8f), u(30f), 6.56f, 0.5f, fillAlpha = 0.5f, width = 0f)
        diamond(left + u(28.9f), titleTopPx - u(4f), 19.69f, 0.9f, fillAlpha = 0.25f, width = 2f)
    }
}
