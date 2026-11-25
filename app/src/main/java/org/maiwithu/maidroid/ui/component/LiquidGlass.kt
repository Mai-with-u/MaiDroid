package org.maiwithu.maidroid.ui.component

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedRectangle
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.DialogGlassSurface
import org.maiwithu.maidroid.ui.theme.GlassFallbackSurface
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassShadow
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.LiquidBarSurface

/** 向子树广播当前可用的实时液态玻璃采样背景（如弹窗表面导出的 LayerBackdrop 或顶栏采样背景）。 */
internal val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 创建纯色画布采样背景：供不处于透明浮层之上（如实心卡片内部）的开关与按钮做零自采样依赖的液态玻璃绘制。 */
@Composable
internal fun rememberSurfaceBackdrop(color: Color = CardSurface): Backdrop {
    val latestColor = remember { mutableStateOf(color) }
    latestColor.value = color
    // 在绘制快照作用域内读取颜色状态：底色更新时自动触发 DrawBackdropNode 重绘并复用同一 Backdrop 实例。
    return rememberCanvasBackdrop { drawRect(latestColor.value) }
}

/** 优先读取外层传递的动态玻璃采样源；未挂载采样源时自动回退至卡片实心背景画布。 */
@Composable
internal fun resolveLiquidBackdrop(fallbackColor: Color = CardSurface): Backdrop =
    LocalLiquidBackdrop.current ?: rememberSurfaceBackdrop(fallbackColor)

/**
 * 全应用悬浮控件共用的液态玻璃容器修饰符（对标 AndroidLiquidGlass 1.0.6）：
 * - Android 13+（API 33+）：同时启用色彩增强、实时高斯模糊、AGSL 边缘凸镜折射与高光边线；
 * - Android 12（API 31–32）：启用 RenderEffect 实时高斯模糊与色彩增强，`lens()` 自动跳过；
 * - Android 8–11（API 26–30）：回落至暗玻璃底色与微光边框，保持轮廓与可读性。
 */
internal fun Modifier.liquidGlassSurface(
    backdrop: Backdrop,
    shape: () -> Shape,
    blurRadius: Dp = 12.dp,
    refractionHeight: Dp = 18.dp,
    refractionAmount: Dp = 24.dp,
    depthEffect: Boolean = false,
    surfaceColor: Color = LiquidBarSurface,
    fallbackSurfaceColor: Color = GlassFallbackSurface,
    highlight: (() -> Highlight?)? = { DefaultLiquidGlassHighlight },
    shadow: (() -> Shadow?)? = { DefaultLiquidGlassShadow },
    innerShadow: (() -> InnerShadow?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
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
                lens(refractionHeight.toPx(), refractionAmount.toPx(), depthEffect = depthEffect)
            },
            highlight = highlight,
            shadow = shadow,
            innerShadow = innerShadow,
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = {
                drawRect(if (hasRenderEffect) surfaceColor else fallbackSurfaceColor)
            }
        )
        .then(borderOverlay)
}

/**
 * 弹窗卡片专用的深景深液态玻璃表面修饰符（对标官方 DialogContent Demo）：
 * 使用 RoundedRectangle 连续圆角，叠加色彩微调、柔和模糊、depthEffect 边缘折射与 Highlight.Plain，
 * 并支持导出 exportedBackdrop 让弹窗内部的液态按钮与开关可以直接折射弹窗本体的内容。
 */
internal fun Modifier.liquidGlassDialogSurface(
    backdrop: Backdrop,
    shape: () -> Shape = { RoundedRectangle(Corners.Card) },
    blurRadius: Dp = 14.dp,
    refractionHeight: Dp = 24.dp,
    refractionAmount: Dp = 44.dp,
    surfaceColor: Color = DialogGlassSurface,
    fallbackSurfaceColor: Color = CardSurface.copy(alpha = 0.94f),
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
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
                colorControls(brightness = -0.02f, saturation = 1.35f)
                blur(blurRadius.toPx())
                lens(refractionHeight.toPx(), refractionAmount.toPx(), depthEffect = true)
            },
            highlight = { Highlight.Plain },
            shadow = { DefaultDialogGlassShadow },
            layerBlock = layerBlock,
            exportedBackdrop = exportedBackdrop,
            onDrawSurface = {
                drawRect(if (hasRenderEffect) surfaceColor else fallbackSurfaceColor)
            }
        )
        .then(borderOverlay)
}

/** 轻量级内部悬浮滑块修饰符（无外阴影，供尚未切至三层结构的简洁容器使用）。 */
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

internal val DefaultLiquidGlassHighlight = Highlight(
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

private val DefaultDialogGlassShadow = Shadow(
    radius = 22.dp,
    color = Color.Black.copy(alpha = 0.34f)
)
