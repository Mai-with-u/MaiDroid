package org.maiwithu.maidroid.ui.theme

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.maiwithu.maidroid.ui.component.BounceOverscrollFactory

/**
 * Material 组件的默认配色（深色与浅色）：主色均为品牌橙，其余中性色跟随各主题色板。
 * 页面大多通过 Color.kt 的语义访问器读取当前色板，这里负责同步 Material 组件与 CompositionLocal。
 */
private val MaiDroidDarkColorScheme = darkColorScheme(
    primary = BrandOrange,
    onPrimary = OnBrandOrange,
    primaryContainer = BrandOrangeMuted,
    onPrimaryContainer = OnBrandOrange,
    secondary = BrandOrangeMuted,
    onSecondary = OnBrandOrange,
    secondaryContainer = BrandOrangeMuted,
    onSecondaryContainer = OnBrandOrange,
    background = DarkMaiDroidColors.oobeBackground,
    onBackground = DarkMaiDroidColors.oobeTextPrimary,
    surface = DarkMaiDroidColors.oobeSurface,
    onSurface = DarkMaiDroidColors.oobeTextPrimary,
    surfaceVariant = DarkMaiDroidColors.oobeSurfaceVariant,
    onSurfaceVariant = DarkMaiDroidColors.oobeTextSecondary,
    outline = DarkMaiDroidColors.cardBorder,
    outlineVariant = DarkMaiDroidColors.cardDivider
)

private val MaiDroidLightColorScheme = lightColorScheme(
    primary = BrandOrange,
    onPrimary = OnBrandOrange,
    primaryContainer = BrandOrangeMuted,
    onPrimaryContainer = OnBrandOrange,
    secondary = BrandOrangeMuted,
    onSecondary = OnBrandOrange,
    secondaryContainer = BrandOrangeMuted,
    onSecondaryContainer = OnBrandOrange,
    background = LightMaiDroidColors.oobeBackground,
    onBackground = LightMaiDroidColors.oobeTextPrimary,
    surface = LightMaiDroidColors.oobeSurface,
    onSurface = LightMaiDroidColors.oobeTextPrimary,
    surfaceVariant = LightMaiDroidColors.oobeSurfaceVariant,
    onSurfaceVariant = LightMaiDroidColors.oobeTextSecondary,
    outline = LightMaiDroidColors.cardBorder,
    outlineVariant = LightMaiDroidColors.cardDivider
)

/** 全应用主题：双色语义配色 + 抖音美好体 / 鸿蒙黑体字体 + 全局弹性阻尼越界滚动，所有界面都包在它里面。 */
@Composable
fun MaiDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val palette = if (darkTheme) DarkMaiDroidColors else LightMaiDroidColors
    val colorScheme = if (darkTheme) MaiDroidDarkColorScheme else MaiDroidLightColorScheme
    val density = LocalDensity.current
    // 用统一的阻尼平移工厂替代 Android 12+ 默认的画面拉伸变形，保证任何列表到顶/到底都不会拉扁文字和图标。
    val maxOverscrollPx = with(density) { 260.dp.toPx() }
    val overscrollFactory = remember(maxOverscrollPx) {
        BounceOverscrollFactory(maxOverscrollPx = maxOverscrollPx)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaiDroidTypography,
    ) {
        CompositionLocalProvider(
            LocalMaiDroidColors provides palette,
            LocalOverscrollFactory provides overscrollFactory
        ) {
            ProvideTextStyle(value = MaiDroidTypography.bodyLarge) {
                content()
            }
        }
    }
}
