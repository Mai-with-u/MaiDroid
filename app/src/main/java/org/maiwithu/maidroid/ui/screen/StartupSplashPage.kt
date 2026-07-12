package org.maiwithu.maidroid.ui.screen

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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.DouyinSans
import org.maiwithu.maidroid.ui.theme.HarmonySans

private const val StartupSplashArtDesignWidth = 424f
private const val StartupSplashArtDesignHeight = 917f
private const val StartupSplashArtOffsetX = -6f
private const val StartupSplashInfoOffsetX = 49f
private const val StartupSplashInfoOffsetY = 773f
private const val StartupSplashInfoWidth = 314f
private const val StartupSplashInfoHeight = 96f
private const val StartupSplashInfoHorizontalMargin = 16f
private const val StartupSplashSideBlurRadius = 24f

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
    val scaleX = widthDp.coerceAtLeast(0f) / HomeDesignWidth
    val scaleY = heightDp.coerceAtLeast(0f) / HomeDesignHeight

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
    val sceneWidth = HomeDesignWidth * scale
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

@Composable
internal fun StartupSplashPage(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .background(Color(0xFF1C1C1E))
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
                StartupSideBlurLayer(
                    blurTarget = blurTarget,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(sideWidth)
                        .fillMaxHeight()
                )
                StartupSideBlurLayer(
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
private fun StartupSideBlurLayer(
    blurTarget: BlurTarget?,
    modifier: Modifier = Modifier
) {
    val overlayColor = Color.Black.copy(alpha = 0.06f).toArgb()

    AndroidView(
        factory = { context -> BlurView(context) },
        update = { view ->
            if (blurTarget != null && view.tag !== blurTarget) {
                view.tag = blurTarget
                view.setupWith(blurTarget, 5f, false)
                    .setBlurRadius(StartupSplashSideBlurRadius)
                    .setOverlayColor(overlayColor)
                    .setBlurAutoUpdate(true)
            } else if (blurTarget != null) {
                view.setBlurEnabled(true)
                view.setBlurAutoUpdate(true)
                view.setBlurRadius(StartupSplashSideBlurRadius)
                view.setOverlayColor(overlayColor)
            } else {
                view.setBlurEnabled(false)
                view.setOverlayColor(overlayColor)
            }
        },
        modifier = modifier
    )
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
                color = Color(0x542B2B2B),
                shape = infoShape
            )
    ) {
        StartupInfoBlurLayer(
            blurTarget = blurTarget,
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
                    color = Color.White,
                    fontFamily = DouyinSans,
                    fontSize = scaledSp(20f),
                    fontWeight = FontWeight.Bold,
                    lineHeight = scaledSp(21f),
                    maxLines = 1,
                    modifier = Modifier.width(x(202f))
                )

                Text(
                    text = "一个正在努力成为人类的BOT",
                    color = Color.White,
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

@Composable
private fun StartupInfoBlurLayer(
    blurTarget: BlurTarget?,
    modifier: Modifier = Modifier
) {
    val overlayColor = SubtleGlassSurface.toArgb()

    AndroidView(
        factory = { context -> BlurView(context) },
        update = { view ->
            if (blurTarget != null && view.tag !== blurTarget) {
                view.tag = blurTarget
                view.setupWith(blurTarget, 5f, false)
                    .setBlurRadius(SubtleGlassBlurRadius)
                    .setOverlayColor(overlayColor)
                    .setBlurAutoUpdate(true)
            } else if (blurTarget != null) {
                view.setBlurEnabled(true)
                view.setBlurAutoUpdate(true)
                view.setBlurRadius(SubtleGlassBlurRadius)
                view.setOverlayColor(overlayColor)
            } else {
                view.setBlurEnabled(false)
                view.setOverlayColor(overlayColor)
            }
        },
        modifier = modifier
    )
}
