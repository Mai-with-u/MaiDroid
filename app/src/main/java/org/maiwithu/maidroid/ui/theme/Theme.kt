package org.maiwithu.maidroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * MaiDroid dark color scheme based on the Figma OOBE design.
 *
 * Key colors:
 * - Primary/Accent: Orange #E97F0F
 * - Background: #121212
 * - Surface (cards): #2B2B2B
 * - Text: White with varying opacity levels
 */
private val MaiDroidColorScheme = darkColorScheme(
    primary = Orange500,
    onPrimary = TextOnOrange,
    primaryContainer = Orange400,
    onPrimaryContainer = TextOnOrange,
    secondary = Orange400,
    onSecondary = TextOnOrange,
    secondaryContainer = Orange400,
    onSecondaryContainer = TextOnOrange,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = TextSecondary,
    outline = Orange400,
    outlineVariant = Orange300
)

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
