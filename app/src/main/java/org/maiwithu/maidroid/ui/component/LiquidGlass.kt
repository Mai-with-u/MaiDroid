package org.maiwithu.maidroid.ui.component

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import org.maiwithu.maidroid.ui.theme.GlassFallbackSurface
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassShadow
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface

/**
 * 主页悬浮控件共用的液态玻璃容器修饰符（基于 AndroidLiquidGlass 1.0.6）：
 * - Android 13+（API 33+）：同时启用色彩饱和度增强、实时高斯模糊、AGSL 边缘凸镜折射与高光边线；
 * - Android 12（API 31–32）：启用 RenderEffect 实时高斯模糊与色彩增强，`lens()` 内部自动安全跳过；
 * - Android 8–11（API 26–30）：改用高对比暗玻璃兜底底色与微光边框，保持文字可读性与视觉轮廓。
 */
internal fun Modifier.liquidGlassSurface(
    backdrop: Backdrop,
    shape: () -> Shape,
    blurRadius: Dp = 14.dp,
    refractionHeight: Dp = 14.dp,
    refractionAmount: Dp = 24.dp,
    surfaceColor: Color = GlassSurface,
    fallbackSurfaceColor: Color = GlassFallbackSurface,
    highlight: (() -> Highlight?)? = { DefaultLiquidGlassHighlight },
    shadow: (() -> Shadow?)? = { DefaultLiquidGlassShadow },
    exportedBackdrop: LayerBackdrop? = null
): Modifier {
    val activeShape = shape()
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val borderOverlay = if (hasRenderEffect) {
        Modifier
    } else {
        Modifier.border(1.dp, GlassStroke, activeShape)
    }

    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(refractionHeight.toPx(), refractionAmount.toPx())
            },
            highlight = highlight,
            shadow = shadow,
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = {
                drawRect(if (hasRenderEffect) surfaceColor else fallbackSurfaceColor)
            }
        )
        .then(borderOverlay)
}

/**
 * 导航栏内选中滑块用的轻量液态玻璃指示器：
 * 在外层玻璃之上再叠一层柔光折射胶囊，去掉重外阴影保持内部干净通透。
 */
internal fun Modifier.liquidGlassIndicator(
    backdrop: Backdrop,
    shape: () -> Shape,
    refractionHeight: Dp = 10.dp,
    refractionAmount: Dp = 18.dp,
    surfaceColor: Color = GlassSelectedIndicator
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = shape,
    effects = {
        lens(refractionHeight.toPx(), refractionAmount.toPx())
    },
    highlight = { IndicatorLiquidGlassHighlight },
    shadow = null,
    onDrawSurface = {
        drawRect(surfaceColor)
    }
)

private val DefaultLiquidGlassHighlight = Highlight(
    width = 0.8.dp,
    alpha = 0.88f,
    style = HighlightStyle.Default(
        color = GlassStroke,
        angle = 45f,
        falloff = 1.15f
    )
)

private val IndicatorLiquidGlassHighlight = Highlight(
    width = 0.6.dp,
    alpha = 0.72f,
    style = HighlightStyle.Ambient(intensity = 0.32f)
)

private val DefaultLiquidGlassShadow = Shadow(
    radius = 18.dp,
    color = GlassShadow
)
