package org.maiwithu.maidroid.ui.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.roundToInt

private val HeaderRowHeight = 42.dp
private val TopBarFeatherHeight = 32.dp
private val TopBarBlurRadius = 18.dp
private val TopBarBlurExtraSampleHeight = 24.dp

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
 * 吸顶连续缩小标题 + 毛玻璃渐进模糊容器。
 *
 * 全程只渲染唯一标题与动作按钮，随滚动通过 GPU 层缩放与垂直位移平滑收进状态栏下方顶栏，
 * 同时将滚动内容录制成同一 Compose 层的 [GraphicsLayer]，在 Android 12+ 上直接做高羽化渐进高斯模糊，
 * 不额外嵌套 AndroidView BlurTarget，与外层底部悬浮胶囊导航的毛玻璃宿主完全兼容。
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

    val useProgressiveBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && glassAlpha > 0.005f
    val contentLayer = rememberGraphicsLayer()
    val recordModifier = if (useProgressiveBlur) {
        // 仅在出现滚动重叠时将页面内容录进离屏图层供顶栏实时采样；静止在顶部时保持零额外开销。
        Modifier.drawWithContent {
            contentLayer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(contentLayer)
        }
    } else {
        Modifier
    }

    // 在暗色背景上叠加渐进渐变遮罩：Android 12+ 搭配底层模糊留出透色，加厚上半区以稳住白色标题对比度。
    val topScrimBrush = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Brush.verticalGradient(
            0.0f to PageBackground.copy(alpha = 0.90f * glassAlpha),
            0.48f to PageBackground.copy(alpha = 0.74f * glassAlpha),
            0.78f to PageBackground.copy(alpha = 0.30f * glassAlpha),
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
                .then(recordModifier),
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
                if (useProgressiveBlur) {
                    // 向下多采样一段高度给高斯核参考，再经 DstIn 渐变遮罩抹平下缘，避免底边出现透明边缘暗线。
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
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                // 放开高度上限并贴顶对齐，确保 drawLayer 坐标 (0,0) 与下方内容区严格 1:1 重合。
                                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                .height(totalBarHeight + TopBarBlurExtraSampleHeight)
                                .blur(radius = TopBarBlurRadius, edgeTreatment = BlurredEdgeTreatment.Rectangle)
                                .drawBehind {
                                    drawLayer(contentLayer)
                                }
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

        // 单一标题行盖在渐变毛玻璃顶部之上，用 offset 保持点击热区、无障碍边界与视觉位置始终一致。
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

            // 以展开大字号一次性光栅化后在 GPU 层连续缩小，避免字号阶跃与双影重叠；右侧留空随缩放安全收窄。
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

/** 顶栏共用的 42dp 玻璃圆形图标按钮，展开态 1.0x 大方易点，吸顶态平滑微缩至约 38dp。 */
@Composable
internal fun GlassHeaderCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = CircleShape
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(shape)
            .background(GlassButtonSurface)
            .border(0.8.dp, GlassStroke, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = TextPrimary,
            modifier = Modifier.size(22.dp)
        )
    }
}
