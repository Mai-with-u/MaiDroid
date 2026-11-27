package org.maiwithu.maidroid.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 全应用配色。按用途命名：同一语义色只在一套双色板中定义，页面通过主题作用域自动响应浅/深色切换。

/** 外观主题策略：默认跟随系统深浅色切换，也可在设置中手动锁定为浅色或深色。 */
internal enum class AppThemeMode(val storageValue: String, val label: String) {
    System("system", "跟随系统"),
    Light("light", "浅色"),
    Dark("dark", "深色");

    companion object {
        fun fromStorage(raw: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == raw } ?: System
    }
}

/** 结合用户偏好与系统当前深色状态解析本帧是否启用深色主题。 */
internal fun resolveUseDarkTheme(mode: AppThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (mode) {
        AppThemeMode.System -> systemInDarkTheme
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }

// ── 主题无关恒定色：品牌橙、白字锚点、高饱和功能图标圆底与光学白画布 ─────────────────────
val BrandOrange = Color(0xFFE97F0F)
val BrandOrangeMuted = Color(0xCCE97F0F)  // 80% 不透明，OOBE 的按钮和图标底色
val OnBrandOrange = Color.White

// ── 清新扁平功能图标色板：明快不浑浊的实色圆形徽章底（深浅模式通用） ───────────────────
val IconPeachOrange = Color(0xFFFF8A3D)
val IconSkyBlue = Color(0xFF389BFF)
val IconMintGreen = Color(0xFF22C58B)
val IconSunnyAmber = Color(0xFFF5A623)
val IconFreshGreen = Color(0xFF32C766)
val IconIrisBlue = Color(0xFF6366F1)
val IconCyanBlue = Color(0xFF22B8CF)
val IconCoralPink = Color(0xFFFF6B8B)
val IconTealMint = Color(0xFF20C997)
val IconWarmCoral = Color(0xFFFF7A45)
val IconRoseAlert = Color(0xFFF25C69)
val PlatformDiscord = Color(0xFF5865F2)
val PlatformSignal = Color(0xFF3B82F6)

val QrCanvasBackground = Color.White          // 二维码与远程桌面画布光学白底，确保扫码识别率
const val SubtleGlassBlurRadius = 8f

// 启动页的插画按这个尺寸（dp）的设计稿绘制，运行时按实际屏幕等比缩放。
const val DesignFrameWidthDp = 412f
const val DesignFrameHeightDp = 917f

/** 随深浅外观动态切换的全应用语义色板，禁止在页面里直接硬编码黑白底色与边框色。 */
@Immutable
internal data class MaiDroidColors(
    val isDark: Boolean,
    val oobeBackground: Color,
    val oobeSurface: Color,
    val oobeSurfaceVariant: Color,
    val oobeTextPrimary: Color,
    val oobeTextSecondary: Color,
    val oobeTextDimmed: Color,
    val pageBackground: Color,
    val textPrimary: Color,
    val cardSurface: Color,
    val cardBorder: Color,
    val cardSurfaceSelected: Color,
    val segmentedThumbSurface: Color,
    val cardDivider: Color,
    val dialogInnerSurface: Color,
    val dialogBackdropScrim: Color,
    val dialogFooterTray: Color,
    val textMuted: Color,
    val textFaint: Color,
    val switchTrackUnchecked: Color,
    val switchThumbUnchecked: Color,
    val splashEdgeShade: Color,
    val splashSideBlurOverlay: Color,
    val statusOnline: Color,
    val statusWarning: Color,
    val statusDanger: Color,
    val glassSurface: Color,
    val glassFallbackSurface: Color,
    val glassStroke: Color,
    val subtleGlassSurface: Color,
    val glassButtonSurface: Color,
    val glassSelectedIndicator: Color,
    val glassShadow: Color,
    val liquidBarSurface: Color,
    val dialogGlassSurface: Color,
    val liquidButtonSurface: Color,
    val dialogGlassShadow: Color,
    val terminalBackground: Color,
    val terminalText: Color,
    val bootInk: Color,
    val bootTrack: Color,
    val bootText: Color,
    val bootTextDim: Color,
    val bootTextFaint: Color,
    val bootAccent: Color
)

/** 深色配色：主页/终端/唤醒页采用 OLED 纯黑 #000000 基底，OOBE 保留深灰 #121212。 */
internal val DarkMaiDroidColors = MaiDroidColors(
    isDark = true,
    oobeBackground = Color(0xFF121212),
    oobeSurface = Color(0xFF2B2B2B),
    oobeSurfaceVariant = Color(0xFF1E1E1E),
    oobeTextPrimary = Color(0xFFFAFAFA),
    oobeTextSecondary = Color(0x80FFFFFF),
    oobeTextDimmed = Color(0x66FFFFFF),
    pageBackground = Color(0xFF000000),
    textPrimary = Color.White,
    cardSurface = Color(0xFF1A1B1F),
    cardBorder = Color(0xFF262830),
    cardSurfaceSelected = Color(0xFF30333C),
    segmentedThumbSurface = Color(0xFF30333C),
    cardDivider = Color(0x12FFFFFF),
    dialogInnerSurface = Color(0xFF000000).copy(alpha = 0.56f),
    dialogBackdropScrim = Color(0xFF000000).copy(alpha = 0.52f),
    dialogFooterTray = Color.Black.copy(alpha = 0.09f),
    textMuted = Color(0xFF8E929B),
    textFaint = Color(0xFF6B6F78),
    switchTrackUnchecked = Color(0xFF32353E),
    switchThumbUnchecked = Color(0xFFE2E4EA),
    splashEdgeShade = Color(0x24000000),
    splashSideBlurOverlay = Color(0x0F000000),
    statusOnline = Color(0xFF30D158),
    statusWarning = Color(0xFFE8A064),
    statusDanger = Color(0xFFFF453A),
    glassSurface = Color(0x471E2026),
    glassFallbackSurface = Color(0xD91A1C22),
    glassStroke = Color(0x29FFFFFF),
    subtleGlassSurface = Color(0x2E202228),
    glassButtonSurface = Color(0x40202228),
    glassSelectedIndicator = Color(0x26FFFFFF),
    glassShadow = Color(0x3D000000),
    liquidBarSurface = Color(0x70121418),
    dialogGlassSurface = Color(0xBA14161B),
    liquidButtonSurface = Color(0x26FFFFFF),
    dialogGlassShadow = Color.Black.copy(alpha = 0.34f),
    terminalBackground = Color(0xFF000000),
    terminalText = Color(0xFFD0D0D4),
    bootInk = Color(0xFF000000),
    bootTrack = Color(0xFF22262B),
    bootText = Color(0xFFECECE7),
    bootTextDim = Color(0xFF9A9EA5),
    bootTextFaint = Color(0xFF5C6168),
    bootAccent = BrandOrange
)

/** 浅色配色：清透冷灰基底配纯白卡片，确保文字对比度与白玻璃边缘轮廓清晰耐看。 */
internal val LightMaiDroidColors = MaiDroidColors(
    isDark = false,
    oobeBackground = Color(0xFFF3F4F8),
    oobeSurface = Color(0xFFFFFFFF),
    oobeSurfaceVariant = Color(0xFFECEFF5),
    oobeTextPrimary = Color(0xFF16181D),
    oobeTextSecondary = Color(0xFF5C6270),
    oobeTextDimmed = Color(0xFF8C92A0),
    pageBackground = Color(0xFFF3F4F8),
    textPrimary = Color(0xFF16181D),
    cardSurface = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE3E6EE),
    cardSurfaceSelected = Color(0xFFE9EDF5),
    segmentedThumbSurface = Color(0xFFFFFFFF),
    cardDivider = Color(0x140F172A),
    dialogInnerSurface = Color(0xD9F1F3F8),
    dialogBackdropScrim = Color(0x42000000),
    dialogFooterTray = Color(0x0A0F172A),
    textMuted = Color(0xFF5C6270),
    textFaint = Color(0xFF858B99),
    switchTrackUnchecked = Color(0xFFD5DAE4),
    switchThumbUnchecked = Color(0xFFFFFFFF),
    splashEdgeShade = Color(0x12000000),
    splashSideBlurOverlay = Color(0x14FFFFFF),
    statusOnline = Color(0xFF1FA849),
    statusWarning = Color(0xFFC86D14),
    statusDanger = Color(0xFFE03131),
    glassSurface = Color(0xB8FFFFFF),
    glassFallbackSurface = Color(0xEEFFFFFF),
    glassStroke = Color(0x1F0F172A),
    subtleGlassSurface = Color(0x99FFFFFF),
    glassButtonSurface = Color(0xBDFFFFFF),
    glassSelectedIndicator = Color(0x140F172A),
    glassShadow = Color(0x1A0F172A),
    liquidBarSurface = Color(0xC7F8F9FC),
    dialogGlassSurface = Color(0xE0F9FAFD),
    liquidButtonSurface = Color(0x120F172A),
    dialogGlassShadow = Color(0x240F172A),
    terminalBackground = Color(0xFFF6F7FA),
    terminalText = Color(0xFF1F2329),
    bootInk = Color(0xFFF4F5F8),
    bootTrack = Color(0xFFDDE1EA),
    bootText = Color(0xFF16181D),
    bootTextDim = Color(0xFF525866),
    bootTextFaint = Color(0xFF8A909E),
    bootAccent = BrandOrange
)

/** 向全应用子树广播当前深浅主题色板；脱离 MaiDroidTheme（如部分单测/预览）时默认回落至深色板。 */
internal val LocalMaiDroidColors = staticCompositionLocalOf { DarkMaiDroidColors }

// ── 响应式色彩访问器：保持全站原有令牌名零侵入切换浅/深色 ──────────────────────────────
val OobeBackground: Color = DarkMaiDroidColors.oobeBackground
val OobeSurface: Color = DarkMaiDroidColors.oobeSurface
val OobeSurfaceVariant: Color = DarkMaiDroidColors.oobeSurfaceVariant
val OobeTextPrimary: Color = DarkMaiDroidColors.oobeTextPrimary
val OobeTextSecondary: Color = DarkMaiDroidColors.oobeTextSecondary
val OobeTextDimmed: Color = DarkMaiDroidColors.oobeTextDimmed

val PageBackground: Color = DarkMaiDroidColors.pageBackground
val TextPrimary: Color = DarkMaiDroidColors.textPrimary
val CardSurface: Color = DarkMaiDroidColors.cardSurface
val CardBorder: Color = DarkMaiDroidColors.cardBorder
val CardSurfaceSelected: Color = DarkMaiDroidColors.cardSurfaceSelected
internal val SegmentedThumbSurface: Color = DarkMaiDroidColors.segmentedThumbSurface
val CardDivider: Color = DarkMaiDroidColors.cardDivider
val DialogInnerSurface: Color = DarkMaiDroidColors.dialogInnerSurface
val DialogBackdropScrim: Color = DarkMaiDroidColors.dialogBackdropScrim
val DialogFooterTray: Color = DarkMaiDroidColors.dialogFooterTray
val TextMuted: Color = DarkMaiDroidColors.textMuted
val TextFaint: Color = DarkMaiDroidColors.textFaint

val SwitchTrackUnchecked: Color = DarkMaiDroidColors.switchTrackUnchecked
val SwitchThumbUnchecked: Color = DarkMaiDroidColors.switchThumbUnchecked

val SplashEdgeShade: Color = DarkMaiDroidColors.splashEdgeShade
val SplashSideBlurOverlay: Color = DarkMaiDroidColors.splashSideBlurOverlay

val StatusOnline: Color = DarkMaiDroidColors.statusOnline
val StatusWarning: Color = DarkMaiDroidColors.statusWarning
val StatusDanger: Color = DarkMaiDroidColors.statusDanger

val GlassSurface: Color = DarkMaiDroidColors.glassSurface
val GlassFallbackSurface: Color = DarkMaiDroidColors.glassFallbackSurface
val GlassStroke: Color = DarkMaiDroidColors.glassStroke
val SubtleGlassSurface: Color = DarkMaiDroidColors.subtleGlassSurface
val GlassButtonSurface: Color = DarkMaiDroidColors.glassButtonSurface
val GlassSelectedIndicator: Color = DarkMaiDroidColors.glassSelectedIndicator
val GlassShadow: Color = DarkMaiDroidColors.glassShadow
val LiquidBarSurface: Color = DarkMaiDroidColors.liquidBarSurface
val DialogGlassSurface: Color = DarkMaiDroidColors.dialogGlassSurface
val LiquidButtonSurface: Color = DarkMaiDroidColors.liquidButtonSurface
internal val DialogGlassShadow: Color = DarkMaiDroidColors.dialogGlassShadow

val TerminalBackground: Color = DarkMaiDroidColors.terminalBackground
val TerminalText: Color = DarkMaiDroidColors.terminalText

val BootInk: Color = DarkMaiDroidColors.bootInk
val BootTrack: Color = DarkMaiDroidColors.bootTrack
val BootText: Color = DarkMaiDroidColors.bootText
val BootTextDim: Color = DarkMaiDroidColors.bootTextDim
val BootTextFaint: Color = DarkMaiDroidColors.bootTextFaint
val BootAccent: Color = DarkMaiDroidColors.bootAccent
