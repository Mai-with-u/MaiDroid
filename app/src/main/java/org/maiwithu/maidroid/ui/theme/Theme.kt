package org.maiwithu.maidroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

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
    outline = BrandOrangeMuted,
    outlineVariant = BrandOrangeFaint
)

/** 全应用主题：深色配色 + 抖音美好体 / 鸿蒙黑体字体，所有界面都包在它里面。 */
@Composable
fun MaiDroidTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MaiDroidColorScheme,
        typography = MaiDroidTypography,
    ) {
        ProvideTextStyle(value = MaiDroidTypography.bodyLarge) {
            content()
        }
    }
}
