package org.maiwithu.maidroid.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme

private val HomeBackground = Color(0xFF07090D)
private val HomeOrange = Color(0xFFE8921E)
private val HomeTextBlue = Color(0xFFA0B4C8)
private val HomeMutedBlue = Color(0xFF788CA0)
private val HomeOnline = Color(0xFF64C88C)
private val HomeOffline = Color(0xFFE8A064)
private const val HomeDesignWidth = 412f
private const val HomeDesignHeight = 917f

@Composable
fun HomeScreen(
    webUiOnline: Boolean,
    versionName: String,
    onWakeMai: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(HomeBackground)
    ) {
        val sx = maxWidth.value / HomeDesignWidth
        val sy = maxHeight.value / HomeDesignHeight
        val textScale = minOf(sx, sy)
        fun x(value: Float): Dp = (value * sx).dp
        fun y(value: Float): Dp = (value * sy).dp
        fun sp(value: Float) = (value * textScale).sp

        Image(
            painter = painterResource(R.drawable.home_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .offset(x = x(0f), y = y(-4f))
                .width(x(600f))
                .height(y(789f))
                .graphicsLayer { alpha = 0.5f }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(y(156.75f))
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
                .height(y(384.04f))
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
            sx = sx,
            sy = sy,
            textScale = textScale
        )

        BootTelemetry(
            sx = sx,
            sy = sy,
            textScale = textScale
        )

        Text(
            text = "麦麦Bot",
            color = Color.White,
            fontSize = sp(48f),
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = sp(4f),
            lineHeight = sp(52f),
            modifier = Modifier
                .offset(x = x(40f), y = y(588f))
                .width(x(220f))
        )

        Text(
            text = "MaiSaka × MaiDroid",
            color = HomeOrange.copy(alpha = 0.85f),
            fontSize = sp(16f),
            fontWeight = FontWeight.Bold,
            letterSpacing = sp(6f),
            lineHeight = sp(20f),
            modifier = Modifier
                .offset(x = x(39f), y = y(643f))
                .width(x(315f))
        )

        Box(
            modifier = Modifier
                .offset(x = x(39f), y = y(667f))
                .width(x(303f))
                .height(y(3f))
                .background(HomeOrange.copy(alpha = 0.25f))
        )

        Text(
            text = "一个正在努力成为人类的BOT。",
            color = Color(0xFFB4C3D7).copy(alpha = 0.7f),
            fontSize = sp(12f),
            fontWeight = FontWeight.Bold,
            letterSpacing = sp(1f),
            lineHeight = sp(16f),
            modifier = Modifier
                .offset(x = x(40f), y = y(679f))
                .width(x(240f))
        )

        WakeMaiButton(
            onClick = onWakeMai,
            modifier = Modifier
                .offset(x = x(38f), y = y(706f))
                .width(x(335.7f))
                .height(y(59.6f)),
            sx = sx,
            textScale = textScale
        )

        Text(
            text = if (webUiOnline) {
                "MAIBOT READY  ◇  TAP TO CONTINUE"
            } else {
                "WEBUI OFFLINE  ◇  TAP TO WAKE"
            },
            color = Color(0xFF96A5B9).copy(alpha = 0.4f),
            fontSize = sp(7.05f),
            letterSpacing = sp(2f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = y(15f))
                .width(x(335.7f))
        )
    }
}

@Composable
private fun HeaderLabels(
    webUiOnline: Boolean,
    versionName: String,
    sx: Float,
    sy: Float,
    textScale: Float
) {
    fun x(value: Float): Dp = (value * sx).dp
    fun y(value: Float): Dp = (value * sy).dp
    fun sp(value: Float) = (value * textScale).sp

    Text(
        text = "MAISAKA",
        color = HomeOrange.copy(alpha = 0.9f),
        fontSize = sp(8.62f),
        letterSpacing = sp(4f),
        modifier = Modifier
            .offset(x = x(31f), y = y(44f))
            .width(x(90f))
    )

    Text(
        text = "牢麦待机中",
        color = HomeTextBlue.copy(alpha = 0.7f),
        fontSize = sp(7.05f),
        letterSpacing = sp(1.5f),
        modifier = Modifier
            .offset(x = x(31f), y = y(58f))
            .width(x(96f))
    )

    Text(
        text = "v$versionName",
        color = HomeMutedBlue.copy(alpha = 0.6f),
        fontSize = sp(7.84f),
        textAlign = TextAlign.End,
        modifier = Modifier
            .offset(x = x(320f), y = y(45f))
            .width(x(58f))
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = Modifier
            .offset(x = x(300f), y = y(56f))
            .width(x(78f))
    ) {
        Box(
            modifier = Modifier
                .size(x(3.9f))
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (webUiOnline) HomeOnline else HomeOffline)
        )
        Spacer(modifier = Modifier.width(x(4f)))
        Text(
            text = if (webUiOnline) "ONLINE" else "OFFLINE",
            color = if (webUiOnline) HomeOnline.copy(alpha = 0.8f) else HomeOffline.copy(alpha = 0.82f),
            fontSize = sp(7.05f),
            letterSpacing = sp(2f)
        )
    }
}

@Composable
private fun BootTelemetry(
    sx: Float,
    sy: Float,
    textScale: Float
) {
    fun x(value: Float): Dp = (value * sx).dp
    fun y(value: Float): Dp = (value * sy).dp
    fun sp(value: Float) = (value * textScale).sp

    Text(
        text = "114°51'N  41°91'E",
        color = Color(0xFF788CAA).copy(alpha = 0.4f),
        fontSize = sp(6.27f),
        letterSpacing = sp(1f),
        modifier = Modifier
            .offset(x = x(16.8f), y = y(352.7f))
            .width(x(124f))
    )

    Text(
        text = "MAI.BOOT  //  AWAITING INPUT",
        color = Color(0xFFC8A064).copy(alpha = 0.35f),
        fontSize = sp(6.27f),
        letterSpacing = sp(1.5f),
        modifier = Modifier
            .offset(x = x(16.8f), y = y(362.1f))
            .width(x(180f))
    )
}

@Composable
private fun WakeMaiButton(
    onClick: () -> Unit,
    modifier: Modifier,
    sx: Float,
    textScale: Float
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .shadow(
                elevation = (7f * textScale).dp,
                shape = WakeButtonShape,
                ambientColor = HomeOrange.copy(alpha = 0.28f),
                spotColor = HomeOrange.copy(alpha = 0.5f)
            )
            .clip(WakeButtonShape)
            .background(HomeOrange)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
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
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = (16f * sx).dp)
                .size((30f * textScale).dp)
        )

        Text(
            text = "唤醒麦麦",
            color = Color.White,
            fontSize = (24f * textScale).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (4f * textScale).sp,
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
        val sx = size.width / HomeDesignWidth
        val sy = size.height / HomeDesignHeight
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

@Preview(
    name = "Home / Online",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 917
)
@Composable
private fun HomeScreenOnlinePreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = true,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Offline",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 917
)
@Composable
private fun HomeScreenOfflinePreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = false,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Tall Ratio",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 360,
    heightDp = 900
)
@Composable
private fun HomeScreenTallRatioPreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = true,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}

@Preview(
    name = "Home / Short Ratio",
    showBackground = true,
    backgroundColor = 0xFF07090D,
    widthDp = 412,
    heightDp = 760
)
@Composable
private fun HomeScreenShortRatioPreview() {
    MaiDroidTheme {
        HomeScreen(
            webUiOnline = false,
            versionName = "1.0",
            onWakeMai = {}
        )
    }
}
