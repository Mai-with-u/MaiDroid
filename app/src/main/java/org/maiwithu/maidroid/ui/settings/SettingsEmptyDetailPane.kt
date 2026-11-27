package org.maiwithu.maidroid.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.InteractiveHighlight
import org.maiwithu.maidroid.ui.component.appPageBackground
import org.maiwithu.maidroid.ui.component.computeLiquidButtonDragTransform
import org.maiwithu.maidroid.ui.component.liquidGlassSurface
import org.maiwithu.maidroid.ui.component.rememberThemedLayerBackdrop
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.IconMintGreen
import org.maiwithu.maidroid.ui.theme.PageBackground

/**
 * 设置页横屏/宽屏右侧默认空态：
 * 不堆砌文字、标题或说明，只在右栏中央留一枚双层同心液态玻璃应用图标，
 * 外层液态玻璃托盘采样克制的居中柔光渐变并支持轻触果冻弹性，内层在图标上方叠无模糊液态玻璃凸镜折射层。
 */
@Composable
internal fun SettingsEmptyDetailPane(
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val traySize = if (compact) 116.dp else 136.dp
    val trayCornerRadius = if (compact) 30.dp else 34.dp
    val iconSize = if (compact) 92.dp else 108.dp
    val iconCornerRadius = if (compact) 18.dp else 20.dp
    val trayShape = remember(trayCornerRadius) { RoundedRectangle(trayCornerRadius) }
    val iconShape = remember(iconCornerRadius) { RoundedRectangle(iconCornerRadius) }

    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val ambientBackdrop = rememberThemedLayerBackdrop(PageBackground)
    val iconLayerBackdrop = rememberLayerBackdrop()

    Box(
        modifier = modifier
            .fillMaxSize()
            .appPageBackground(PageBackground),
        contentAlignment = Alignment.Center
    ) {
        // 录制极克制的居中暖橙-薄荷柔光晕并叠加水印基底，供外层液态玻璃托盘产生真实光学折射色散。
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(ambientBackdrop)
                .appPageBackground(PageBackground),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(traySize * 2.2f)
                    .background(
                        Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to BrandOrange.copy(alpha = 0.16f),
                                0.45f to IconMintGreen.copy(alpha = 0.06f),
                                1f to Color.Transparent
                            )
                        )
                    )
            )
        }

        // 外层液态玻璃托盘：同心连续圆角（30dp/34dp），带高透模糊、边缘折射、内阴影与轻触果冻形变。
        Box(
            modifier = Modifier
                .size(traySize)
                .liquidGlassSurface(
                    backdrop = ambientBackdrop,
                    shape = { trayShape },
                    blurRadius = 16.dp,
                    refractionHeight = 20.dp,
                    refractionAmount = 32.dp,
                    depthEffect = true,
                    surfaceColor = GlassSurface,
                    highlight = { Highlight.Default },
                    shadow = {
                        Shadow(
                            radius = 24.dp,
                            color = Color.Black.copy(alpha = 0.36f)
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 8.dp,
                            alpha = 0.24f
                        )
                    },
                    layerBlock = {
                        val transform = computeLiquidButtonDragTransform(
                            widthPx = size.width,
                            heightPx = size.height,
                            maxDragHeightPx = 4.dp.toPx(),
                            pressProgress = highlight.pressProgress,
                            offsetX = highlight.offset.x,
                            offsetY = highlight.offset.y
                        )
                        translationX = transform.translationXPx
                        translationY = transform.translationYPx
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                    }
                )
                .then(highlight.modifier)
                .then(highlight.gestureModifier),
            contentAlignment = Alignment.Center
        ) {
            // 内层严禁叠加 blur，确保图标本体锐利清晰，只在表面覆一层无模糊液态玻璃凸镜高光。
            Box(
                modifier = Modifier.size(iconSize),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.startup_splash_app_icon),
                    contentDescription = "MaiDroid",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(iconLayerBackdrop)
                        .clip(iconShape)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = iconLayerBackdrop,
                            shape = { iconShape },
                            effects = {
                                vibrancy()
                                lens(
                                    refractionHeight = 10.dp.toPx(),
                                    refractionAmount = 18.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true
                                )
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = 0.65f)
                            },
                            shadow = null,
                            onDrawSurface = {
                                drawRect(
                                    Brush.linearGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.14f),
                                            Color.Transparent,
                                            Color.White.copy(alpha = 0.04f)
                                        )
                                    )
                                )
                            }
                        )
                )
            }
        }
    }
}
