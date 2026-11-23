package org.maiwithu.maidroid.ui.component

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.roundToInt

private val HeaderRowHeight = 42.dp
private val TopBarFeatherHeight = 32.dp
private val TopBarBlurRadius = 18.dp

// 顶栏从上到下的渐进模糊透明度遮罩：实心区稳住状态栏与小标题可读性，下沿逐渐羽化消隐，彻底消除硬切线。
private val ProgressiveBlurAlphaMask = Brush.verticalGradient(
    0.0f to Color.Black,
    0.54f to Color.Black,
    0.76f to Color.Black.copy(alpha = 0.52f),
    0.90f to Color.Black.copy(alpha = 0.14f),
    1.0f to Color.Transparent
)

/** 根据 LazyColumn 首项滚动位置计算顶部大标题折叠进度（0f 展开，1f 完全吸顶）。 */
internal fun computeLazyHeaderCollapseProgress(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    collapseDistancePx: Float
): Float {
    if (firstVisibleItemIndex > 0) return 1f
    if (collapseDistancePx <= 0f) return 0f
    return (firstVisibleItemScrollOffset.coerceAtLeast(0) / collapseDistancePx).coerceIn(0f, 1f)
}

/** 根据垂直 ScrollState 偏移计算顶部大标题折叠进度（0f 展开，1f 完全吸顶）。 */
internal fun computeScrollHeaderCollapseProgress(
    scrollOffsetPx: Int,
    collapseDistancePx: Float
): Float {
    if (collapseDistancePx <= 0f) return 0f
    return (scrollOffsetPx.coerceAtLeast(0) / collapseDistancePx).coerceIn(0f, 1f)
}

/** 顶栏毛玻璃与渐变遮罩的显现比例：刚一开始滚动即提前过渡，避免下方卡片生硬撞入状态栏。 */
internal fun computeGlassBarScrimAlpha(collapseProgress: Float): Float =
    (collapseProgress.coerceIn(0f, 1f) / 0.65f).coerceIn(0f, 1f)

/** 单一标题随折叠进度的连续缩放比例：从 1.0 倍大标题顺滑缩小至紧凑吸顶字号，消除双标题硬切与叠影。 */
internal fun computeCollapsingTitleScale(
    collapseProgress: Float,
    expandedSp: Float,
    collapsedSp: Float
): Float {
    if (expandedSp <= 0f) return 1f
    val minScale = (collapsedSp / expandedSp).coerceIn(0.4f, 1f)
    val progress = collapseProgress.coerceIn(0f, 1f)
    return 1f + (minScale - 1f) * progress
}

/** 顶栏圆形动作按钮随折叠进度的连续缩放比例：与标题同步微缩至吸顶尺寸。 */
internal fun computeCollapsingActionScale(
    collapseProgress: Float,
    expandedDp: Float = 42f,
    collapsedDp: Float = 38f
): Float {
    if (expandedDp <= 0f) return 1f
    val minScale = (collapsedDp / expandedDp).coerceIn(0.6f, 1f)
    val progress = collapseProgress.coerceIn(0f, 1f)
    return 1f + (minScale - 1f) * progress
}

/** 标题行相对状态栏下沿的垂直位移（px）：随折叠平滑上移收进顶栏，展开态下同步跟随顶部下拉视差过冲。 */
internal fun computeCollapsingHeaderOffsetY(
    collapseProgress: Float,
    expandedTopPx: Float,
    collapsedTopPx: Float,
    headerOverscrollPx: Float = 0f
): Float {
    val progress = collapseProgress.coerceIn(0f, 1f)
    return expandedTopPx + (collapsedTopPx - expandedTopPx) * progress + headerOverscrollPx * (1f - progress)
}

/** 列表大标题下方的副标题透明度：在标题折叠过半前提前淡出，避免上滑过程中撞入缩小的标题。 */
internal fun computeHeaderSubtitleAlpha(collapseProgress: Float): Float =
    (1f - collapseProgress.coerceIn(0f, 1f) * 2.2f).coerceIn(0f, 1f)

/**
 * 吸顶连续缩小标题 + AndroidLiquidGlass 渐进模糊容器：
 * - 通过 `rememberLayerBackdrop` 录制下方滚动内容并传递给顶栏圆钮（返回/帮助）实现实时玻璃折射；
 * - Android 13+（API 33+）采用 AGSL 渐进羽化着色器 + drawPlainBackdrop，
 *   Android 12（API 31–32）使用 drawPlainBackdrop + DstIn 羽化渐变遮罩，Android 8–11 回退至深色渐隐遮罩。
 */
@Composable
internal fun CollapsingGlassHeaderContainer(
    title: String,
    collapseProgress: Float,
    statusBarTop: Dp,
    horizontalPadding: Dp,
    expandedTopOffset: Dp,
    modifier: Modifier = Modifier,
    expandedFontSize: TextUnit = TextSizes.Display,
    collapsedFontSize: TextUnit = 19.sp,
    topBarHeight: Dp = 52.dp,
    headerOverscrollPx: Float = 0f,
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val clampedProgress = collapseProgress.coerceIn(0f, 1f)
    val glassAlpha = computeGlassBarScrimAlpha(clampedProgress)
    val totalBarHeight = statusBarTop + topBarHeight + TopBarFeatherHeight
    val collapsedTopOffset = ((topBarHeight - HeaderRowHeight) / 2f).coerceAtLeast(0.dp)
    val safeExpandedTopOffset = maxOf(expandedTopOffset, collapsedTopOffset)

    val density = LocalDensity.current
    val expandedTopPx = with(density) { safeExpandedTopOffset.toPx() }
    val collapsedTopPx = with(density) { collapsedTopOffset.toPx() }
    val leadingTightenPx = with(density) { 4.dp.toPx() }

    val headerOffsetY = computeCollapsingHeaderOffsetY(
        collapseProgress = clampedProgress,
        expandedTopPx = expandedTopPx,
        collapsedTopPx = collapsedTopPx,
        headerOverscrollPx = headerOverscrollPx
    )
    val titleScale = computeCollapsingTitleScale(
        collapseProgress = clampedProgress,
        expandedSp = expandedFontSize.value,
        collapsedSp = collapsedFontSize.value
    )
    val actionScale = computeCollapsingActionScale(clampedProgress)

    val contentBackdrop = rememberLayerBackdrop {
        drawRect(PageBackground)
        drawContent()
    }

    val topScrimBrush = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Brush.verticalGradient(
            0.0f to PageBackground.copy(alpha = 0.88f * glassAlpha),
            0.48f to PageBackground.copy(alpha = 0.70f * glassAlpha),
            0.78f to PageBackground.copy(alpha = 0.28f * glassAlpha),
            1.0f to Color.Transparent
        )
    } else {
        Brush.verticalGradient(
            0.0f to PageBackground.copy(alpha = 0.96f * glassAlpha),
            0.52f to PageBackground.copy(alpha = 0.88f * glassAlpha),
            0.82f to PageBackground.copy(alpha = 0.42f * glassAlpha),
            1.0f to Color.Transparent
        )
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(contentBackdrop),
            content = content
        )

        if (glassAlpha > 0.005f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(totalBarHeight)
                    .clipToBounds()
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .drawPlainBackdrop(
                                backdrop = contentBackdrop,
                                shape = { RectangleShape },
                                effects = {
                                    blur(TopBarBlurRadius.toPx())
                                    effect(
                                        RenderEffect.createRuntimeShaderEffect(
                                            obtainRuntimeShader(
                                                "HeaderProgressiveMask",
                                                HeaderProgressiveShaderSource
                                            ).apply {
                                                setFloatUniform("size", size.width, size.height)
                                                setFloatUniform("alphaScale", glassAlpha)
                                            },
                                            "content"
                                        )
                                    )
                                }
                            )
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer {
                                alpha = glassAlpha
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                drawRect(brush = ProgressiveBlurAlphaMask, blendMode = BlendMode.DstIn)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .drawPlainBackdrop(
                                    backdrop = contentBackdrop,
                                    shape = { RectangleShape },
                                    effects = {
                                        blur(TopBarBlurRadius.toPx())
                                    }
                                )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(topScrimBrush)
                )
            }
        }

        // 把顶栏底部实时采样源共享给返回/帮助圆形按钮，保持静态与滚动期统一的液态玻璃光学响应
        CompositionLocalProvider(LocalLiquidBackdrop provides contentBackdrop) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = statusBarTop, start = horizontalPadding, end = horizontalPadding)
                    .offset { IntOffset(0, headerOffsetY.roundToInt()) }
                    .height(HeaderRowHeight),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingAction != null) {
                    Box(
                        modifier = Modifier.graphicsLayer {
                            scaleX = actionScale
                            scaleY = actionScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                    ) {
                        leadingAction()
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = expandedFontSize,
                    fontWeight = FontWeight.Bold,
                    lineHeight = (expandedFontSize.value + 4f).sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            scaleX = titleScale
                            scaleY = titleScale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            if (leadingAction != null) {
                                translationX = -leadingTightenPx * clampedProgress
                            }
                        }
                )

                if (trailingAction != null) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier.graphicsLayer {
                            scaleX = actionScale
                            scaleY = actionScale
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        }
                    ) {
                        trailingAction()
                    }
                }
            }
        }
    }
}

/** 顶栏共用的 42dp 液态玻璃圆形图标按钮，按压具备果冻微胀与高光折射。 */
@Composable
internal fun GlassHeaderCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidGlassIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        size = 42.dp,
        iconSize = 22.dp,
        modifier = modifier
    )
}

private const val HeaderProgressiveShaderSource = """
uniform shader content;
uniform float2 size;
uniform float alphaScale;

half4 main(float2 coord) {
    float t = clamp(coord.y / max(size.y, 1.0), 0.0, 1.0);
    float mask = 1.0 - smoothstep(0.54, 0.96, t);
    return content.eval(coord) * (mask * alphaScale);
}
"""
