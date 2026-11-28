package org.maiwithu.maidroid.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import org.maiwithu.maidroid.BuildConfig
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Debug 构建纯黑页面背景叠加的斜向标识文案，前后各留 4 个空格以分隔平铺间距。 */
internal const val DebugWatermarkText = "    MaiDroid · 开发中内容，请以实际上线内容为准！    "

/** 背景斜纹逆时针倾斜角度（度）。 */
internal const val DebugWatermarkAngleDegrees = -24f

/** 深色底上的水印纯白不透明度，保持高辨识度且不抢卡片与正文阅读视线。 */
internal const val DebugWatermarkAlpha = 0.085f

/** 浅色底上的水印深墨灰不透明度，柔和可见且不干扰阅读。 */
internal const val DebugWatermarkLightBgAlpha = 0.055f

private const val DebugWatermarkTextSizeSp = 13f
private const val DebugWatermarkRowSpacingDp = 56f

/** 判断当前构建是否需要在页面基底叠加“开发中”背景水印。 */
internal fun shouldShowDebugBackgroundWatermark(isDebugBuild: Boolean): Boolean = isDebugBuild

/** 判断页面背景是否属于浅色底面，决定水印选用深色墨迹还是纯白柔光。 */
internal fun isLightSurfaceColor(color: Color): Boolean = color.luminance() > 0.5f

/** 依据页面基底明暗返回对应水印画笔 ARGB 颜色（纯位运算组装，便于 JVM 单测直接校验）。 */
internal fun resolveDebugWatermarkInkArgb(backgroundColor: Color): Int =
    if (isLightSurfaceColor(backgroundColor)) {
        val alphaByte = (DebugWatermarkLightBgAlpha * 255f).roundToInt().coerceIn(0, 255)
        (alphaByte shl 24) or 0x0F172A
    } else {
        val alphaByte = (DebugWatermarkAlpha * 255f).roundToInt().coerceIn(0, 255)
        (alphaByte shl 24) or 0xFFFFFF
    }

/** 根据实测文本宽度与单行行距计算两行错行瓦片尺寸（向上取整且保底为 1px，避免零宽高贴图崩溃）。 */
internal fun computeDebugWatermarkTileDimensions(
    textWidthPx: Float,
    rowSpacingPx: Float
): Pair<Int, Int> {
    val safeWidth = if (textWidthPx.isFinite()) ceil(textWidthPx.toDouble()).toInt() else 1
    val safeHeight = if (rowSpacingPx.isFinite()) ceil((rowSpacingPx * 2f).toDouble()).toInt() else 1
    return maxOf(1, safeWidth) to maxOf(1, safeHeight)
}

/**
 * 全应用页面及分栏纯黑背景统一入口：
 * - Debug 包在纯黑基底之上绘制斜向无缝对齐水印，不改动页面原有层级与弹窗/浮层遮挡关系；
 * - Release 包退化为标准纯色 `.background(color)`，零额外纹理或着色器开销。
 */
internal fun Modifier.appPageBackground(
    color: Color,
    enabled: Boolean = BuildConfig.DEBUG
): Modifier = if (shouldShowDebugBackgroundWatermark(enabled)) {
    this.then(AppPageBackgroundElement(color))
} else {
    this.background(color)
}

private data class AppPageBackgroundElement(
    val color: Color
) : ModifierNodeElement<AppPageBackgroundNode>() {

    override fun create(): AppPageBackgroundNode = AppPageBackgroundNode(color)

    override fun update(node: AppPageBackgroundNode) {
        if (node.color != color) {
            node.color = color
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "appPageBackground"
        properties["color"] = color
    }
}

private class AppPageBackgroundNode(
    var color: Color
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var originInRoot: Offset = Offset.Zero
    private val shaderMatrix = Matrix()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (!coordinates.isAttached) return
        val nextOrigin = coordinates.positionInRoot()
        if (nextOrigin != originInRoot) {
            originInRoot = nextOrigin
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawRect(color)
        val density = requireDensity()
        val inkArgb = resolveDebugWatermarkInkArgb(color)
        val shader = DebugWatermarkTileCache.shaderFor(
            density = density.density,
            fontScale = density.fontScale,
            inkArgb = inkArgb
        )
        // 图案在 Root 窗口原点坐标系下统一旋转与平铺，再减去当前容器偏移，使双栏/弹窗跨分界线完全无缝对齐。
        shaderMatrix.reset()
        shaderMatrix.setRotate(DebugWatermarkAngleDegrees)
        shaderMatrix.postTranslate(-originInRoot.x, -originInRoot.y)
        shader.setLocalMatrix(shaderMatrix)
        fillPaint.shader = shader
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, fillPaint)
        }
        drawContent()
    }
}

/** 按 `(density, fontScale, inkArgb)` 全局复用单张两行交错砖纹纹理瓦片，切深浅色主题立即切换墨色且避免频繁重建贴图。 */
private object DebugWatermarkTileCache {
    private class CachedTile(
        val densityBits: Int,
        val fontScaleBits: Int,
        val inkArgb: Int,
        val bitmap: Bitmap,
        val shader: BitmapShader
    )

    private var cached: CachedTile? = null

    @Synchronized
    fun shaderFor(density: Float, fontScale: Float, inkArgb: Int): BitmapShader {
        val safeDensity = if (density.isFinite() && density > 0f) density else 1f
        val safeFontScale = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
        val densityBits = safeDensity.toRawBits()
        val fontScaleBits = safeFontScale.toRawBits()
        val current = cached
        if (current != null &&
            current.densityBits == densityBits &&
            current.fontScaleBits == fontScaleBits &&
            current.inkArgb == inkArgb &&
            !current.bitmap.isRecycled
        ) {
            return current.shader
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkArgb
            textSize = DebugWatermarkTextSizeSp * safeDensity * safeFontScale
            typeface = Typeface.DEFAULT
            isSubpixelText = true
        }
        val rowSpacingPx = DebugWatermarkRowSpacingDp * safeDensity
        val textWidthPx = textPaint.measureText(DebugWatermarkText)
        val (tileWidth, tileHeight) = computeDebugWatermarkTileDimensions(
            textWidthPx = textWidthPx,
            rowSpacingPx = rowSpacingPx
        )

        val bitmap = Bitmap.createBitmap(tileWidth, tileHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val metrics = textPaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) * 0.5f
        val row0Y = rowSpacingPx * 0.5f + baselineOffset
        val row1Y = rowSpacingPx * 1.5f + baselineOffset
        val halfShift = tileWidth * 0.5f

        // 偶数行居中平铺，奇数行错开半个瓦片宽并补全左半接缝，斜向铺满后呈现整齐砖纹。
        canvas.drawText(DebugWatermarkText, 0f, row0Y, textPaint)
        canvas.drawText(DebugWatermarkText, halfShift, row1Y, textPaint)
        canvas.drawText(DebugWatermarkText, -halfShift, row1Y, textPaint)

        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cached = CachedTile(
            densityBits = densityBits,
            fontScaleBits = fontScaleBits,
            inkArgb = inkArgb,
            bitmap = bitmap,
            shader = shader
        )
        return shader
    }
}
