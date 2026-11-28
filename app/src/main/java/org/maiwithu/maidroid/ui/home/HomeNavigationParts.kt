package org.maiwithu.maidroid.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.LiquidGlassIconButton
import org.maiwithu.maidroid.ui.component.defaultLiquidGlassHighlight
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.LiquidBarSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary
import kotlin.math.abs
import kotlin.math.sqrt

/** 向隐身高亮层中的页签提供实时缩放因子：拖拽水滴滑块时底层激活内容轻度放大以配合凸镜折射。 */
internal val LocalLiquidBottomTabScale = staticCompositionLocalOf { { 1f } }

/** 横向四栏底栏拖拽高亮时的凸镜放大上限：兼顾透镜折射感与四字标签完整防裁切。 */
internal const val HomeNavHorizontalAccentScale = 1.14f

/** 纵向窄胶囊（64dp 宽横屏侧栏与竖屏 WebUI 展开胶囊）拖拽高亮时的凸镜放大上限。 */
internal const val HomeNavVerticalAccentScale = 1.10f

/** 导航标签允许跟随系统字号放大的上限，防止大字号模式下四字标签溢出圆形/椭圆选中指示器。 */
internal const val HomeNavMaxFontScale = 1.03f

/**
 * 导航栏单个标签页的图标与字号排版参数：
 * 结合胶囊内切弦宽收敛高度与字号，保证四字中文标签左右边角完整落入选中指示器水滴之内。
 */
internal data class HomeNavTabTypography(
    val iconSizeDp: Float,
    val labelGapDp: Float,
    val labelSizeSp: Float,
    val labelLineHeightSp: Float
) {
    val totalContentHeightDp: Float
        get() = iconSizeDp + labelGapDp + labelLineHeightSp
}

/** 按槽位形态（竖屏 WebUI 展开 56dp 窄胶囊、紧凑横屏椭圆侧栏 vs 平板横屏椭圆侧栏与宽底栏）返回内切安全排版参数。 */
internal fun resolveHomeNavTabTypography(
    narrowVertical: Boolean,
    compact: Boolean
): HomeNavTabTypography = when {
    // 竖屏 WebUI 展开 56dp 窄侧边胶囊（48×38 紧凑横向药丸内槽）：17dp 图标配 8.5sp 字号，75% 饱满填充且四字不溢边
    narrowVertical -> HomeNavTabTypography(
        iconSizeDp = 17f,
        labelGapDp = 1.5f,
        labelSizeSp = 8.5f,
        labelLineHeightSp = 10f
    )
    // 手机紧凑横屏椭圆侧栏（72dp 宽、64×46/64×44 内槽）：保持清晰 11sp 标签不溢边
    compact -> HomeNavTabTypography(
        iconSizeDp = 20f,
        labelGapDp = 2f,
        labelSizeSp = 11f,
        labelLineHeightSp = 13f
    )
    // 平板横屏椭圆侧栏（64×48 内槽）与竖屏底栏（62~75×48 椭圆）：2dp 紧凑间距配 48dp 内槽，内容占比 75% 不发空
    else -> HomeNavTabTypography(
        iconSizeDp = 21f,
        labelGapDp = 2f,
        labelSizeSp = 11f,
        labelLineHeightSp = 13f
    )
}

/**
 * 将导航标签实际渲染字号倍率钳位在 0.85..HomeNavMaxFontScale 内：
 * 系统调大字体后抵消多余缩放，避免圆形水滴内外出现“一半白一半橙”的溢字分层。
 */
internal fun clampHomeNavLabelSp(baseSp: Float, systemFontScale: Float): Float {
    if (systemFontScale <= 0f) return baseSp
    val effectiveScale = systemFontScale.coerceIn(0.85f, HomeNavMaxFontScale)
    return baseSp * effectiveScale / systemFontScale
}

/** 计算全圆 Capsule() 槽位在距垂直中心 [offsetFromCenterYDp] 处的水平内切弦宽（dp）。 */
internal fun computeCapsuleChordWidthDp(
    widthDp: Float,
    heightDp: Float,
    offsetFromCenterYDp: Float
): Float {
    if (widthDp <= 0f || heightDp <= 0f) return 0f
    val y = abs(offsetFromCenterYDp)
    if (y >= heightDp * 0.5f) return 0f
    val r = minOf(widthDp, heightDp) * 0.5f
    return if (heightDp <= widthDp) {
        val chord = 2f * sqrt((r * r - y * y).coerceAtLeast(0f))
        (widthDp - heightDp) + chord
    } else {
        val capCenterY = heightDp * 0.5f - r
        if (y <= capCenterY) {
            widthDp
        } else {
            val dy = y - capCenterY
            2f * sqrt((r * r - dy * dy).coerceAtLeast(0f))
        }
    }
}

/** 主页导航的四个标签，顺序即底栏从左到右、横屏侧栏从上到下的顺序（也决定切换动画的方向）。 */
internal enum class HomeTab(val label: String) {
    WebUi("WebUI"),
    Platforms("消息平台"),
    Extensions("扩展组件"),
    Settings("设置")
}

/**
 * 首页导航统一基准短边（dp）：
 * 竖屏底栏高度、竖屏 WebUI 侧边胶囊宽度（收起与展开）以及横竖屏独立正圆终端按钮直径全部对齐至 56dp，
 * 4dp 内边距后竖屏 WebUI 收起高亮呈 48×48 同心全圆、展开高亮收敛为 48×38 紧凑药丸、底栏呈 63~75×48 椭圆，形变切换全程零粗细跳变。
 */
internal const val HomeNavBarHeightDp = 56f

/** 竖屏底部导航栏与独立正圆终端按钮的统一高度。 */
internal val PortraitBottomBarHeight = HomeNavBarHeightDp.dp

/** 竖屏底部导航栏离容器底边的留白。 */
internal val PortraitBottomBarBottomPadding = 16.dp

/** 竖屏底部导航栏与容器左右边缘的留白（收紧边距，为四栏标签让出横向空间）。 */
internal val PortraitBottomBarHorizontalPadding = 16.dp

/** 竖屏底部导航胶囊与右下角圆形终端按钮之间的安全间距。 */
internal val PortraitBottomBarGap = 12.dp

/** 单个标签按钮高度（dp 数值常量，同时供竖屏底栏与正常高度横屏侧栏计算复用）。 */
internal const val HomeTabButtonHeightDp = 48f

/** 首页导航外框统一全圆胶囊形状（底栏 56dp 高时呈 28dp 半圆弧，横屏 72dp 侧栏呈 36dp 半圆弧，正方形时呈正圆）。 */
internal val HomeNavBarShape: Shape = Capsule()

/** 首页导航内部滑块与槽位统一全圆胶囊形状（48dp 高时呈 24dp 半圆弧，与 56dp 底栏 4dp 内边距严格同心）。 */
internal val HomeNavIndicatorShape: Shape = Capsule()

/** 计算竖屏底部四栏标签胶囊的目标宽度（dp），为右下角正圆终端按钮与左右留白让出间距。 */
internal fun computeBottomTabBarWidthDp(maxWidthDp: Float): Float {
    val availableWidthDp = maxWidthDp - PortraitBottomBarHorizontalPadding.value * 2f
    val terminalButtonSizeDp = PortraitBottomBarHeight.value
    return minOf(308f, availableWidthDp - terminalButtonSizeDp - PortraitBottomBarGap.value)
        .coerceAtLeast(244f)
}

/** 首页导航未选中页签与工具按钮图标文字统一使用的半透明前景不透明度（78%），与 Tab 栏视觉权重对齐。 */
internal const val HomeNavInactiveContentAlpha = 0.78f

/** 仅在导航水滴被按压、拖拽或回弹过渡期间开启隐身折射层与双源透镜，静止态零额外离屏着色器开销。 */
internal fun shouldRenderLiquidTabRefraction(
    pressProgress: Float,
    isDragging: Boolean
): Boolean = isDragging || pressProgress > 0.001f

/** 全圆液态玻璃终端按钮：沿用与 Tab 栏一致的 LiquidBarSurface 暗玻璃、灰边高光和 78% 灰白前景，降低视觉膨胀感。 */
@Composable
internal fun HomeTerminalButton(
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PortraitBottomBarHeight,
    width: Dp = size,
    height: Dp = size,
    shape: () -> Shape = { HomeNavBarShape },
    enabled: Boolean = true,
    iconSizeDp: Float = 24f
) {
    val navHighlight = defaultLiquidGlassHighlight()
    LiquidGlassIconButton(
        painter = painterResource(R.drawable.ic_terminal_code),
        contentDescription = "查看终端输出",
        onClick = onClick,
        backdrop = backdrop,
        size = size,
        width = width,
        height = height,
        shape = shape,
        iconSize = iconSizeDp.dp,
        tint = TextPrimary.copy(alpha = HomeNavInactiveContentAlpha),
        surfaceColor = LiquidBarSurface,
        highlight = { navHighlight },
        enabled = enabled,
        modifier = modifier
    )
}

/**
 * 单个标签按钮：
 * - 普通底层使用纯白图标/文字并通过透明度区分选中态；
 * - 隐身折射层（[accentHighlighted] = true）使用品牌橙高亮并跟随 [LocalLiquidBottomTabScale] 放大，
 *   让上方拖动的液态玻璃水滴折射出放大高亮图标与文字；点击禁用默认灰水波纹以保持玻璃纯净感。
 */
@Composable
internal fun HomeTabButton(
    tab: HomeTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    narrowVertical: Boolean = false,
    accentHighlighted: Boolean = false,
    clickableEnabled: Boolean = true
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (accentHighlighted || selected) 1f else HomeNavInactiveContentAlpha,
        animationSpec = spring(
            dampingRatio = 1f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "HomeTabContentAlpha"
    )
    val labelColor by animateColorAsState(
        targetValue = if (accentHighlighted) BrandOrange else TextPrimary,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "HomeTabLabelColor"
    )
    val typography = resolveHomeNavTabTypography(narrowVertical = narrowVertical, compact = compact)
    val fontScale = LocalDensity.current.fontScale
    val iconSize = typography.iconSizeDp.dp
    val labelGap = typography.labelGapDp.dp
    val labelSize = clampHomeNavLabelSp(typography.labelSizeSp, fontScale).sp
    val labelLineHeight = clampHomeNavLabelSp(typography.labelLineHeightSp, fontScale).sp
    val accentScale = LocalLiquidBottomTabScale.current

    val clickModifier = if (clickableEnabled) {
        Modifier.clickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = Role.Tab,
            onClick = onClick
        )
    } else {
        Modifier
    }

    // 槽位自身不做 Capsule 裁剪，避免隐身高亮层放大 1.1x+ 时底部四字标签被近圆弧角削去首尾字角。
    Box(
        modifier = modifier.then(clickModifier),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = contentAlpha
                if (accentHighlighted) {
                    val s = accentScale()
                    scaleX = s
                    scaleY = s
                }
            }
        ) {
            HomeTabIcon(
                tab = tab,
                iconSize = iconSize,
                tint = if (accentHighlighted) BrandOrange else TextPrimary
            )

            Spacer(modifier = Modifier.height(labelGap))

            // 显式置零字距覆盖 bodyLarge 默认 0.5sp 字距，防止“消息平台”四字横向撑破圆形高亮泡边界。
            Text(
                text = tab.label,
                color = labelColor,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                lineHeight = labelLineHeight,
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible
            )
        }
    }
}

/** 标签的图标：WebUI 保留麦麦头像原色不染色，其余三个随状态切换纯白或品牌橙。 */
@Composable
private fun HomeTabIcon(
    tab: HomeTab,
    iconSize: Dp,
    tint: Color
) {
    when (tab) {
        HomeTab.WebUi -> Image(
            painter = painterResource(R.drawable.ic_maibot),
            contentDescription = null,
            modifier = Modifier.size(iconSize)
        )

        HomeTab.Platforms -> Icon(
            painter = painterResource(R.drawable.ic_platform_plug),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )

        HomeTab.Extensions -> Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )

        HomeTab.Settings -> Icon(
            imageVector = Icons.Outlined.Settings,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}
