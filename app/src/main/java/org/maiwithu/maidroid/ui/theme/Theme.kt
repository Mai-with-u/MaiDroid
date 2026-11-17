package org.maiwithu.maidroid.ui.theme

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.ui.component.BounceOverscrollFactory

/**
 * Material 组件的默认配色（深色）：主色是品牌橙，背景和卡片取 OOBE 设计稿的灰阶。
 * 主页各页大多直接指定 Color.kt 里的颜色，这里主要影响没显式指定颜色的 Material 组件。
 */
private val MaiDroidColorScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = OnBrandOrange,
    primaryContainer = BrandOrangeMuted,
    onPrimaryContainer = OnBrandOrange,
    secondary = BrandOrangeMuted,
    onSecondary = OnBrandOrange,
    secondaryContainer = BrandOrangeMuted,
    onSecondaryContainer = OnBrandOrange,
    background = OobeBackground,
    onBackground = OobeTextPrimary,
    surface = OobeSurface,
    onSurface = OobeTextPrimary,
    surfaceVariant = OobeSurfaceVariant,
    onSurfaceVariant = OobeTextSecondary,
    outline = CardBorder,
    outlineVariant = CardDivider
)

/** 全应用主题：深色配色 + 抖音美好体 / 鸿蒙黑体字体 + 全局弹性阻尼越界滚动，所有界面都包在它里面。 */
@Composable
fun MaiDroidTheme(
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // 用统一的阻尼平移工厂替代 Android 12+ 默认的画面拉伸变形，保证任何列表到顶/到底都不会拉扁文字和图标。
    val maxOverscrollPx = with(density) { 260.dp.toPx() }
    val overscrollFactory = remember(maxOverscrollPx) {
        BounceOverscrollFactory(maxOverscrollPx = maxOverscrollPx)
    }

    MaterialTheme(
        colorScheme = MaiDroidColorScheme,
        typography = MaiDroidTypography,
    ) {
        CompositionLocalProvider(LocalOverscrollFactory provides overscrollFactory) {
            ProvideTextStyle(value = MaiDroidTypography.bodyLarge) {
                content()
            }
        }
    }
}
