package org.maiwithu.maidroid.ui.splash

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eightbitlab.com.blurview.BlurTarget
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.component.BlurTargetHost
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.DesignFrameHeightDp
import org.maiwithu.maidroid.ui.theme.DesignFrameWidthDp
import org.maiwithu.maidroid.ui.theme.DouyinSans
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.HarmonySans
import org.maiwithu.maidroid.ui.theme.SubtleGlassBlurRadius
import org.maiwithu.maidroid.ui.theme.SubtleGlassSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary

private const val StartupSplashArtDesignWidth = 424f
private const val StartupSplashArtDesignHeight = 917f
private const val StartupSplashArtOffsetX = -6f
private const val StartupSplashInfoOffsetX = 49f
private const val StartupSplashInfoOffsetY = 773f
private const val StartupSplashInfoWidth = 314f
private const val StartupSplashInfoHeight = 96f
private const val StartupSplashInfoHorizontalMargin = 16f
private const val StartupSplashSideBlurRadius = 24f

/** 宽度不小于 600dp 或横屏时插画按高度铺满、两侧留白模糊；否则按设计稿比例铺满竖屏。 */
internal fun startupSplashUsesAdaptiveLayout(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 600f || widthDp > heightDp

internal data class StartupSplashCompactGeometry(
    val scaleX: Float,
    val scaleY: Float,
    val contentScale: Float,
    val artLeftDp: Float,
    val artWidthDp: Float,
    val artHeightDp: Float,
    val infoLeftDp: Float,
    val infoTopDp: Float
)

internal fun startupSplashCompactGeometry(
    widthDp: Float,
    heightDp: Float
): StartupSplashCompactGeometry {
    val scaleX = widthDp.coerceAtLeast(0f) / DesignFrameWidthDp
    val scaleY = heightDp.coerceAtLeast(0f) / DesignFrameHeightDp

    return StartupSplashCompactGeometry(
        scaleX = scaleX,
        scaleY = scaleY,
        contentScale = minOf(scaleX, scaleY),
        artLeftDp = StartupSplashArtOffsetX * scaleX,
        artWidthDp = StartupSplashArtDesignWidth * scaleX,
        artHeightDp = StartupSplashArtDesignHeight * scaleY,
        infoLeftDp = StartupSplashInfoOffsetX * scaleX,
        infoTopDp = StartupSplashInfoOffsetY * scaleY
    )
}

internal data class StartupSplashAdaptiveGeometry(
    val scale: Float,
    val infoScale: Float,
    val sceneLeftDp: Float,
    val artLeftDp: Float,
    val artWidthDp: Float,
    val artHeightDp: Float,
    val infoLeftDp: Float,
    val infoTopDp: Float,
    val infoWidthDp: Float,
    val infoHeightDp: Float
)

internal fun startupSplashAdaptiveGeometry(
    widthDp: Float,
    heightDp: Float
): StartupSplashAdaptiveGeometry {
    val safeWidth = widthDp.coerceAtLeast(0f)
    val safeHeight = heightDp.coerceAtLeast(0f)
    val scale = safeHeight / StartupSplashArtDesignHeight
    val sceneWidth = DesignFrameWidthDp * scale
    val sceneLeft = (safeWidth - sceneWidth) / 2f
    val infoScale = minOf(
        scale,
        (safeWidth - StartupSplashInfoHorizontalMargin * 2f)
            .coerceAtLeast(0f) / StartupSplashInfoWidth
    )
    val infoWidth = StartupSplashInfoWidth * infoScale
    val infoHeight = StartupSplashInfoHeight * infoScale

    return StartupSplashAdaptiveGeometry(
        scale = scale,
        infoScale = infoScale,
        sceneLeftDp = sceneLeft,
        artLeftDp = sceneLeft + StartupSplashArtOffsetX * scale,
        artWidthDp = StartupSplashArtDesignWidth * scale,
        artHeightDp = safeHeight,
        infoLeftDp = (safeWidth - infoWidth) / 2f,
        infoTopDp = StartupSplashInfoOffsetY * scale,
        infoWidthDp = infoWidth,
        infoHeightDp = infoHeight
    )
}

/** 冷启动时的启动页：插画背景 + 底部毛玻璃信息卡（应用名和标语），停留 1.2 秒后由 MaiDroidApp 切走。 */
@Composable
internal fun StartupSplashPage(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .background(CardSurface)
    ) {
        val useAdaptiveLayout = startupSplashUsesAdaptiveLayout(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value
        )
        var blurTarget by remember { mutableStateOf<BlurTarget?>(null) }

        if (useAdaptiveLayout) {
            val geometry = startupSplashAdaptiveGeometry(
                widthDp = maxWidth.value,
                heightDp = maxHeight.value
            )

            BlurTargetHost(
                onTargetChanged = { blurTarget = it },
                modifier = Modifier.matchParentSize()
            ) {
                AdaptiveSplashBackground(modifier = Modifier.fillMaxSize())
            }

            val sideWidth = geometry.artLeftDp.coerceAtLeast(0f).dp
            if (sideWidth > 0.dp) {
                StartupSideBlur(
                    blurTarget = blurTarget,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(sideWidth)
                        .fillMaxHeight()
                )
                StartupSideBlur(
                    blurTarget = blurTarget,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(sideWidth)
                        .fillMaxHeight()
                )
            }

            StartupInfoCard(
                blurTarget = blurTarget,
                originX = (
                    geometry.infoLeftDp - StartupSplashInfoOffsetX * geometry.infoScale
                ).dp,
                originY = (
                    geometry.infoTopDp - StartupSplashInfoOffsetY * geometry.infoScale
                ).dp,
                positionScaleX = geometry.infoScale,
                positionScaleY = geometry.infoScale,
                contentScale = geometry.infoScale
            )
        } else {
            val geometry = startupSplashCompactGeometry(
                widthDp = maxWidth.value,
                heightDp = maxHeight.value
            )

            BlurTargetHost(
                onTargetChanged = { blurTarget = it },
                modifier = Modifier.matchParentSize()
            ) {
                Image(
                    painter = painterResource(R.drawable.startup_splash_background),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .offset(x = geometry.artLeftDp.dp, y = 0.dp)
                        .width(geometry.artWidthDp.dp)
                        .height(geometry.artHeightDp.dp)
                )
            }

            StartupInfoCard(
                blurTarget = blurTarget,
                positionScaleX = geometry.scaleX,
                positionScaleY = geometry.scaleY,
                contentScale = geometry.contentScale
            )
        }
    }
}

@Composable
private fun AdaptiveSplashBackground(modifier: Modifier = Modifier) {
    val image = ImageBitmap.imageResource(R.drawable.startup_splash_background)
    val bitmap = remember(image) { image.asAndroidBitmap() }
    val bitmapPaint = remember(bitmap) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
            shader = BitmapShader(
                bitmap,
                Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP
            )
        }
    }

    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        val artWidth = size.height * (StartupSplashArtDesignWidth / StartupSplashArtDesignHeight)
        val scale = artWidth / bitmap.width.toFloat()
        val artHeight = bitmap.height * scale
        val artLeft = (size.width - artWidth) / 2f
        val artRight = artLeft + artWidth
        val artTop = (size.height - artHeight) / 2f
        val shader = bitmapPaint.shader as BitmapShader
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(artLeft, artTop)
            }
        )

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(
                0f,
                0f,
                size.width,
                size.height,
                bitmapPaint
            )
        }

        val edgeShade = Color.Black.copy(alpha = 0.14f)
        if (artLeft > 0f) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(edgeShade, Color.Transparent),
                    startX = 0f,
                    endX = artLeft
                ),
                size = Size(artLeft, size.height)
            )
        }
        if (artRight < size.width) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, edgeShade),
                    startX = artRight,
                    endX = size.width
                ),
                topLeft = Offset(artRight, 0f),
                size = Size(size.width - artRight, size.height)
            )
        }
    }
}

@Composable
private fun StartupInfoCard(
    blurTarget: BlurTarget?,
    positionScaleX: Float,
    positionScaleY: Float,
    contentScale: Float,
    originX: Dp = 0.dp,
    originY: Dp = 0.dp
) {
    fun x(value: Float): Dp = (value * positionScaleX).dp
    fun y(value: Float): Dp = (value * positionScaleY).dp
    fun d(value: Float): Dp = (value * contentScale).dp
    fun scaledSp(value: Float) = (value * contentScale).sp
    val infoShape = RoundedCornerShape(d(24f))

    Box(
        modifier = Modifier
            .offset(
                x = originX + x(StartupSplashInfoOffsetX),
                y = originY + y(StartupSplashInfoOffsetY)
            )
            .clip(infoShape)
            .border(
                width = 1.dp,
                color = GlassButtonSurface,
                shape = infoShape
            )
    ) {
        BackdropBlurView(
            blurTarget = blurTarget,
            blurRadius = SubtleGlassBlurRadius,
            overlayColor = SubtleGlassSurface,
            modifier = Modifier.matchParentSize()
        )

        Row(
            modifier = Modifier.padding(d(16f)),
            horizontalArrangement = Arrangement.spacedBy(d(16f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.startup_splash_app_icon),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(d(64f))
                    .clip(RoundedCornerShape(d(16f)))
            )

            Column(
                modifier = Modifier
                    .width(x(202f))
                    .height(y(43f))
            ) {
                Text(
                    text = "MaiDroid × MaiSaka",
                    color = TextPrimary,
                    fontFamily = DouyinSans,
                    fontSize = scaledSp(20f),
                    fontWeight = FontWeight.Bold,
                    lineHeight = scaledSp(21f),
                    maxLines = 1,
                    modifier = Modifier.width(x(202f))
                )

                Text(
                    text = "一个正在努力成为人类的BOT",
                    color = TextPrimary,
                    fontFamily = HarmonySans,
                    fontSize = scaledSp(12f),
                    fontWeight = FontWeight.Medium,
                    lineHeight = scaledSp(13f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .offset(y = y(9f))
                        .width(x(200f))
                )
            }
        }
    }
}

// 宽屏时插画两侧的留白：模糊插画边缘并压一层很淡的黑，让留白和插画自然衔接。
@Composable
private fun StartupSideBlur(
    blurTarget: BlurTarget?,
    modifier: Modifier = Modifier
) {
    BackdropBlurView(
        blurTarget = blurTarget,
        blurRadius = StartupSplashSideBlurRadius,
        overlayColor = Color.Black.copy(alpha = 0.06f),
        modifier = modifier
    )
}
