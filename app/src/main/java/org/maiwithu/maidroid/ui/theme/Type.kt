package org.maiwithu.maidroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.maiwithu.maidroid.R

val DouyinSans = FontFamily(
    Font(R.font.douyin_sans_bold, FontWeight.Bold)
)

val HarmonySans = FontFamily(
    Font(R.font.harmonyos_sans_sc_regular, FontWeight.Normal),
    Font(R.font.harmonyos_sans_sc_medium, FontWeight.Medium),
    Font(R.font.harmonyos_sans_sc_bold, FontWeight.Bold)
)

/**
 * Typography matching the Figma design system.
 *
 * Design fonts:
 * - Douyin Sans Bold → used for section titles, weight 700
 * - HarmonyOS Sans SC → used for body text, weights 400/500/700
 *
 * All sizes mapped from the 428pt design width.
 */
val MaiDroidTypography = Typography(
    // Section title: "授予权限" 32sp
    headlineLarge = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 35.sp,
        letterSpacing = 0.sp
    ),
    // Subtitle: 20sp
    headlineMedium = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    // Card title: "存储权限" 24sp
    titleLarge = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // Body text: 18sp
    titleMedium = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    // Button text: "下一步" 36sp
    displaySmall = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    // Action button: "去授权" 20sp
    labelLarge = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    // Tag: "必须"/"可选" 12sp
    labelSmall = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.6.sp
    ),
    // Default body
    bodyLarge = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

// Backward-compatible alias
val Typography = MaiDroidTypography
