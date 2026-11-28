package org.maiwithu.maidroid.ui.component

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.GlassButtonSurface
import org.maiwithu.maidroid.ui.theme.GlassFallbackSurface
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.LiquidButtonSurface
import org.maiwithu.maidroid.ui.theme.LocalMaiDroidColors
import org.maiwithu.maidroid.ui.theme.MaiDroidColors
import org.maiwithu.maidroid.ui.theme.OnBrandOrange
import org.maiwithu.maidroid.ui.theme.SwitchTrackUnchecked
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextPrimary
import kotlin.math.abs

/** 液态玻璃按钮语义风格，统一映射主色着色（BlendMode.Hue）与柔光半透明表面。 */
internal enum class LiquidButtonStyle {
    Primary,
    Secondary,
    SoftAccent,
    Danger,
    SoftDanger
}

private data class LiquidButtonVisual(
    val tint: Color,
    val tintAlpha: Float,
    val surfaceColor: Color,
    val contentColor: Color
)

/**
 * 全应用统一的液态玻璃果冻按钮（完整复刻 Kyant0 LiquidButton 交互物理）：
 * - 按压或轻拽时触发双曲正切微位移 + 角度耦合果冻拉伸 + 随指 AGSL/径向高光；
 * - Android 13+ 实时采样 backdrop 并叠加 vibrancy/blur/lens 边缘折射；
 * - Android 12 实时模糊，Android 8–11 自动叠加高对比边线保证清晰可读。
 */
@Composable
internal fun LiquidButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(),
    style: LiquidButtonStyle = LiquidButtonStyle.Primary,
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    shape: () -> Shape = { Capsule() },
    minHeight: Dp = 44.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    tint: Color = Color.Unspecified,
    surfaceColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val colors = LocalMaiDroidColors.current
    val interactiveHighlight = remember(animationScope, colors.isDark) {
        InteractiveHighlight(animationScope, isDark = colors.isDark)
    }
    val activeInteractive = enabled && isInteractive
    val resolvedShape = shape()
    val visual = resolveLiquidButtonVisual(
        style = style,
        enabled = enabled,
        colors = colors,
        tint = tint,
        surfaceColor = surfaceColor,
        contentColor = contentColor
    )
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.8.dp, GlassStroke, resolvedShape)

    Row(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(12f.dp.toPx(), 24f.dp.toPx())
                },
                highlight = {
                    Highlight.Default.copy(alpha = if (enabled) 0.9f else 0.35f)
                },
                shadow = null,
                layerBlock = if (activeInteractive) {
                    {
                        val transform = computeLiquidButtonDragTransform(
                            widthPx = size.width,
                            heightPx = size.height,
                            maxDragHeightPx = 4f.dp.toPx(),
                            pressProgress = interactiveHighlight.pressProgress,
                            offsetX = interactiveHighlight.offset.x,
                            offsetY = interactiveHighlight.offset.y
                        )
                        translationX = transform.translationXPx
                        translationY = transform.translationYPx
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    if (visual.tint.isSpecified) {
                        drawRect(visual.tint, blendMode = BlendMode.Hue)
                        drawRect(visual.tint.copy(alpha = visual.tintAlpha))
                    }
                    if (visual.surfaceColor.isSpecified) {
                        drawRect(visual.surfaceColor)
                    }
                }
            )
            .then(fallbackBorder)
            .clickable(
                interactionSource = null,
                indication = if (activeInteractive) null else LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (activeInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            )
            .defaultMinSize(minHeight = minHeight)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides visual.contentColor) {
            content()
        }
    }
}

/** 圆形或连续圆角胶囊形的液态玻璃图标按钮：用于终端快捷按钮、顶栏返回/工具按钮、弹窗关闭按钮等，支持自定义宽高与材质高光。 */
@Composable
internal fun LiquidGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(),
    size: Dp = 42.dp,
    width: Dp = size,
    height: Dp = size,
    shape: () -> Shape = { Capsule() },
    enabled: Boolean = true,
    isInteractive: Boolean = true,
    surfaceColor: Color = GlassButtonSurface,
    fallbackSurfaceColor: Color = GlassFallbackSurface,
    blurRadius: Dp = 12.dp,
    refractionHeight: Dp = 14.dp,
    refractionAmount: Dp = 24.dp,
    highlight: (() -> Highlight?)? = { Highlight.Default.copy(alpha = if (enabled) 0.88f else 0.35f) },
    shadow: (() -> Shadow?)? = null,
    content: @Composable () -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val colors = LocalMaiDroidColors.current
    val interactiveHighlight = remember(animationScope, colors.isDark) {
        InteractiveHighlight(animationScope, isDark = colors.isDark)
    }
    val activeInteractive = enabled && isInteractive
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val resolvedShape = shape()
    val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.8.dp, GlassStroke, resolvedShape)
    val defaultShadow = remember(colors.glassShadow) { Shadow(radius = 14.dp, color = colors.glassShadow) }
    val resolvedShadow = shadow ?: { defaultShadow }

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(refractionHeight.toPx(), refractionAmount.toPx())
                },
                highlight = highlight,
                shadow = resolvedShadow,
                layerBlock = if (activeInteractive) {
                    {
                        val transform = computeLiquidButtonDragTransform(
                            widthPx = width.toPx(),
                            heightPx = height.toPx(),
                            maxDragHeightPx = 3f.dp.toPx(),
                            pressProgress = interactiveHighlight.pressProgress,
                            offsetX = interactiveHighlight.offset.x,
                            offsetY = interactiveHighlight.offset.y
                        )
                        translationX = transform.translationXPx
                        translationY = transform.translationYPx
                        scaleX = transform.scaleX
                        scaleY = transform.scaleY
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    drawRect(if (hasRenderEffect) surfaceColor else fallbackSurfaceColor)
                }
            )
            .then(fallbackBorder)
            .clickable(
                interactionSource = null,
                indication = if (activeInteractive) null else LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (activeInteractive) {
                    Modifier
                        .then(interactiveHighlight.modifier)
                        .then(interactiveHighlight.gestureModifier)
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) TextPrimary else TextFaint
        ) {
            content()
        }
    }
}

/** `LiquidGlassIconButton` 矢量图标便捷重载。 */
@Composable
internal fun LiquidGlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(),
    size: Dp = 42.dp,
    width: Dp = size,
    height: Dp = size,
    shape: () -> Shape = { Capsule() },
    iconSize: Dp = 22.dp,
    tint: Color = TextPrimary,
    surfaceColor: Color = GlassButtonSurface,
    highlight: (() -> Highlight?)? = null,
    enabled: Boolean = true
) {
    // 若未定制高光，沿用主函数按可用状态计算的默认高光；首页导航圆钮传入 DefaultLiquidGlassHighlight 对齐 Tab 栏灰边
    val resolvedHighlight = highlight ?: { Highlight.Default.copy(alpha = if (enabled) 0.88f else 0.35f) }
    LiquidGlassIconButton(
        onClick = onClick,
        modifier = modifier,
        backdrop = backdrop,
        size = size,
        width = width,
        height = height,
        shape = shape,
        enabled = enabled,
        surfaceColor = surfaceColor,
        highlight = resolvedHighlight
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else TextFaint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** `LiquidGlassIconButton` 位图/矢量 Painter 便捷重载。 */
@Composable
internal fun LiquidGlassIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(),
    size: Dp = 42.dp,
    width: Dp = size,
    height: Dp = size,
    shape: () -> Shape = { Capsule() },
    iconSize: Dp = 24.dp,
    tint: Color = TextPrimary,
    surfaceColor: Color = GlassButtonSurface,
    highlight: (() -> Highlight?)? = null,
    enabled: Boolean = true
) {
    val resolvedHighlight = highlight ?: { Highlight.Default.copy(alpha = if (enabled) 0.88f else 0.35f) }
    LiquidGlassIconButton(
        onClick = onClick,
        modifier = modifier,
        backdrop = backdrop,
        size = size,
        width = width,
        height = height,
        shape = shape,
        enabled = enabled,
        surfaceColor = surfaceColor,
        highlight = resolvedHighlight
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = if (enabled) tint else TextFaint,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * 液态玻璃水滴开关（完整复刻 Kyant0 LiquidToggle 折射与阻尼物理）：
 * - 按住滑块即膨胀至 1.5x 并由实白变为高透水滴，经 CombinedBackdrop 放大折射下方橙/灰渐变轨道；
 * - 同时支持整颗控件点击切换与左右跟手滑动；若位于垂直列表中被滚动手势打断，自动回滚而不误触发回调；
 * - 若父级在校验不通过（如无悬浮窗权限）时拦截状态变更，自动平滑弹回实际受控状态。
 */
@Composable
internal fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(),
    enabled: Boolean = true
) {
    val isLightTheme = !LocalMaiDroidColors.current.isDark
    val trackWidth = 60.dp
    val trackHeight = 28.dp
    // 保持 28dp 视觉轨道不变，纵向扩展至 40dp 透明热区，方便横屏与弹窗场景下直接点拖开关。
    val touchHeight = 40.dp
    val thumbWidth = 36.dp
    val thumbHeight = 24.dp
    val thumbInset = 2.dp
    val dragDistanceDp = trackWidth - thumbWidth - thumbInset * 2

    val accentColor = BrandOrange.copy(alpha = if (enabled) 1f else 0.45f)
    val trackColor = if (enabled) {
        SwitchTrackUnchecked
    } else {
        SwitchTrackUnchecked.copy(alpha = 0.5f)
    }
    val thumbColor = if (enabled) {
        Color.White
    } else {
        Color.White.copy(alpha = 0.65f)
    }

    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val dragWidthPx = with(density) { dragDistanceDp.toPx() }
    val animationScope = rememberCoroutineScope()

    val latestChecked by rememberUpdatedState(checked)
    val latestOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val latestEnabled by rememberUpdatedState(enabled)

    var didDrag by remember { mutableStateOf(false) }
    var dragDistanceAccumPx by remember { mutableFloatStateOf(0f) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    var syncNonce by remember { mutableIntStateOf(0) }

    val dampedDragAnimation = remember(animationScope, dragWidthPx, touchSlop) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            consumeToggleGestures = true,
            horizontalLockSlopPx = touchSlop * 0.25f,
            onDragStarted = {
                didDrag = false
                dragDistanceAccumPx = 0f
            },
            onDragStopped = {
                val interactive = latestEnabled && latestOnCheckedChange != null
                if (!interactive) {
                    fraction = if (latestChecked) 1f else 0f
                    return@DampedDragAnimation
                }
                // 直接以同步更新的 fraction 结算，避免快速甩动抬手时 snapshotFlow 尚差一帧滞后。
                val nextChecked = resolveToggleDragOutcome(
                    wasDragged = didDrag,
                    cancelled = false,
                    targetFraction = fraction,
                    currentlyChecked = latestChecked
                )
                didDrag = false
                dragDistanceAccumPx = 0f
                fraction = if (nextChecked) 1f else 0f
                if (nextChecked != latestChecked) {
                    latestOnCheckedChange?.invoke(nextChecked)
                    // 外部若拦截了状态改变（例如悬浮窗未授权弹窗），在下一次同步周期主动回弹滑块
                    syncNonce += 1
                } else {
                    animateToValue(fraction)
                }
            },
            onDragCancelled = {
                didDrag = false
                dragDistanceAccumPx = 0f
                fraction = if (latestChecked) 1f else 0f
            },
            onDrag = { _, dragAmount ->
                if (!latestEnabled || latestOnCheckedChange == null) return@DampedDragAnimation
                dragDistanceAccumPx += abs(dragAmount.x)
                if (!didDrag && dragDistanceAccumPx > touchSlop * 0.25f) {
                    didDrag = true
                }
                val delta = dragAmount.x / dragWidthPx.coerceAtLeast(1f)
                fraction = if (isLtr) {
                    (fraction + delta).fastCoerceIn(0f, 1f)
                } else {
                    (fraction - delta).fastCoerceIn(0f, 1f)
                }
            }
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }
            .collectLatest { value ->
                dampedDragAnimation.updateValue(value)
            }
    }
    LaunchedEffect(checked, syncNonce) {
        val target = if (checked) 1f else 0f
        if (target != fraction || abs(dampedDragAnimation.value - target) > 0.01f) {
            fraction = target
            dampedDragAnimation.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()
    val interactiveModifier = if (enabled && onCheckedChange != null) {
        dampedDragAnimation.modifier
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(width = trackWidth, height = touchHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
                if (enabled && onCheckedChange != null) {
                    onClick {
                        latestOnCheckedChange?.invoke(!latestChecked)
                        true
                    }
                }
            }
            .then(interactiveModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.size(width = trackWidth, height = trackHeight),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .layerBackdrop(trackBackdrop)
                    .clip(Capsule())
                    .drawBehind {
                        drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value))
                    }
                    .size(width = trackWidth, height = trackHeight)
            )

            Box(
                Modifier
                    .graphicsLayer {
                        val value = dampedDragAnimation.value
                        val padding = thumbInset.toPx()
                        val travel = dragWidthPx
                        translationX = if (isLtr) {
                            lerp(padding, padding + travel, value)
                        } else {
                            lerp(-padding, -(padding + travel), value)
                        }
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            backdrop,
                            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                val progress = dampedDragAnimation.pressProgress
                                val sx = lerp(2f / 3f, 0.75f, progress)
                                val sy = lerp(0f, 0.75f, progress)
                                scale(sx, sy) {
                                    drawBackdrop()
                                }
                            }
                        ),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            lens(
                                5f.dp.toPx() * progress,
                                10f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(
                                radius = 4f.dp,
                                color = Color.Black.copy(alpha = if (isLightTheme) 0.08f else 0.18f)
                            )
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 4f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 50f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            drawRect(thumbColor.copy(alpha = 1f - progress))
                        }
                    )
                    .size(width = thumbWidth, height = thumbHeight)
            )
        }
    }
}

private fun resolveLiquidButtonVisual(
    style: LiquidButtonStyle,
    enabled: Boolean,
    colors: MaiDroidColors,
    tint: Color,
    surfaceColor: Color,
    contentColor: Color
): LiquidButtonVisual {
    val defaultVisual = when (style) {
        LiquidButtonStyle.Primary -> LiquidButtonVisual(
            tint = BrandOrange,
            tintAlpha = if (enabled) 0.78f else if (colors.isDark) 0.28f else 0.36f,
            surfaceColor = Color.Unspecified,
            contentColor = when {
                enabled -> OnBrandOrange
                colors.isDark -> colors.textFaint
                else -> OnBrandOrange.copy(alpha = 0.85f)
            }
        )

        LiquidButtonStyle.Secondary -> LiquidButtonVisual(
            tint = Color.Unspecified,
            tintAlpha = 0f,
            surfaceColor = if (enabled) colors.liquidButtonSurface else colors.liquidButtonSurface.copy(alpha = 0.45f),
            contentColor = if (enabled) colors.textPrimary else colors.textFaint
        )

        LiquidButtonStyle.SoftAccent -> LiquidButtonVisual(
            tint = Color.Unspecified,
            tintAlpha = 0f,
            surfaceColor = BrandOrange.copy(alpha = if (enabled) (if (colors.isDark) 0.20f else 0.15f) else 0.08f),
            contentColor = if (enabled) BrandOrange else colors.textFaint
        )

        LiquidButtonStyle.Danger -> LiquidButtonVisual(
            tint = colors.statusDanger,
            tintAlpha = if (enabled) 0.80f else 0.32f,
            surfaceColor = Color.Unspecified,
            // 实色危险红按钮内恒用纯白文字，确保浅/深色主题下都具备高对比度。
            contentColor = if (enabled) OnBrandOrange else colors.textFaint
        )

        LiquidButtonStyle.SoftDanger -> LiquidButtonVisual(
            tint = Color.Unspecified,
            tintAlpha = 0f,
            surfaceColor = colors.statusDanger.copy(alpha = if (enabled) 0.16f else 0.08f),
            contentColor = if (enabled) colors.statusDanger else colors.textFaint
        )
    }

    return LiquidButtonVisual(
        tint = if (tint.isSpecified) tint else defaultVisual.tint,
        tintAlpha = if (tint.isSpecified) (if (enabled) 0.76f else 0.28f) else defaultVisual.tintAlpha,
        surfaceColor = if (surfaceColor.isSpecified) surfaceColor else defaultVisual.surfaceColor,
        contentColor = if (contentColor.isSpecified) contentColor else defaultVisual.contentColor
    )
}
