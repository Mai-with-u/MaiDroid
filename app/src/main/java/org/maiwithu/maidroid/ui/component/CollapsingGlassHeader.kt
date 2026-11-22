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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.TextPrimary

private val TopBarContentHeight = 52.dp
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

/** 吸顶小标题的浮现比例：待列表中大标题向上退去后平滑接力，防止大小标题同时叠加。 */
internal fun computePinnedTitleReveal(collapseProgress: Float): Float =
    ((collapseProgress.coerceIn(0f, 1f) - 0.35f) / 0.65f).coerceIn(0f, 1f)

/** 列表内部大标题的渐隐透明度：滚出前提前收淡，避免经过毛玻璃层时产生双影重叠。 */
internal fun computeLargeHeaderAlpha(collapseProgress: Float): Float =
    (1f - collapseProgress.coerceIn(0f, 1f) * 1.45f).coerceIn(0f, 1f)

/**
 * 吸顶折叠标题 + 毛玻璃渐进模糊容器。
 *
 * 将滚动内容录制成同一 Compose 层的 [GraphicsLayer]，在 Android 12+ 上直接于顶栏内做高羽化渐进高斯模糊与融合渐变，
 * 不额外嵌套 AndroidView BlurTarget，从而与外层底部悬浮胶囊导航的毛玻璃宿主完全兼容。
 */
@Composable
internal fun CollapsingGlassHeaderContainer(
    title: String,
    collapseProgress: Float,
    statusBarTop: Dp,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
    leadingAction: (@Composable () -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val clampedProgress = collapseProgress.coerceIn(0f, 1f)
    val glassAlpha = computeGlassBarScrimAlpha(clampedProgress)
    val titleReveal = computePinnedTitleReveal(clampedProgress)
    val totalBarHeight = statusBarTop + TopBarContentHeight + TopBarFeatherHeight
    val density = LocalDensity.current
    val titleTravelPx = with(density) { 6.dp.toPx() }

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

    // 在暗色背景上叠加渐进渐变遮罩：Android 12+ 搭配底层模糊留出透色，旧版加大不透明度保障标题对比度。
    val topScrimBrush = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Brush.verticalGradient(
            0.0f to PageBackground.copy(alpha = 0.86f * glassAlpha),
            0.46f to PageBackground.copy(alpha = 0.68f * glassAlpha),
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

        // 标题与动作按钮独立盖在渐变顶部之上，只在浮现后渲染按钮，防止顶部静止时拦截列表大标题内操作区点击。
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = statusBarTop, start = horizontalPadding, end = horizontalPadding)
                .height(TopBarContentHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingAction != null && titleReveal > 0.05f) {
                Box(
                    modifier = Modifier.graphicsLayer { alpha = titleReveal }
                ) {
                    leadingAction()
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            if (titleReveal > 0.01f) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = titleReveal
                            translationY = (1f - titleReveal) * titleTravelPx
                        }
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            if (trailingAction != null && titleReveal > 0.05f) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier.graphicsLayer { alpha = titleReveal }
                ) {
                    trailingAction()
                }
            }
        }
    }
}

/** 吸顶顶栏共用的 38dp 玻璃圆形图标按钮，与列表页大标题区风格统一。 */
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
            .size(38.dp)
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
            modifier = Modifier.size(21.dp)
        )
    }
}
