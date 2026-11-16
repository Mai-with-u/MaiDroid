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

// 等宽字体，只给唤醒页的开机自检画面用：点线引导要按字符宽度对齐成一列。
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

/**
 * 全局字体排版系统：
 * - DouyinSans（Bold，700）：用于页面与区块主标题
 * - HarmonyOS Sans SC（400/500/700）：用于正文、说明与标签文字
 */
val MaiDroidTypography = Typography(
    // 页面主标题：32sp
    headlineLarge = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 35.sp,
        letterSpacing = 0.sp
    ),
    // 次级标题：20sp
    headlineMedium = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    // 卡片标题：24sp
    titleLarge = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    // 强调正文：18sp
    titleMedium = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    // 醒目大按钮文字：36sp
    displaySmall = TextStyle(
        fontFamily = DouyinSans,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    // 操作按钮文字：20sp
    labelLarge = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),
    // 小型标签（如“必须”“可选”）：12sp
    labelSmall = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.6.sp
    ),
    // 默认正文：16sp
    bodyLarge = TextStyle(
        fontFamily = HarmonySans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)
