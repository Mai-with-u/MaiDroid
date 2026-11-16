package org.maiwithu.maidroid.ui.theme

import androidx.compose.ui.graphics.Color

// 全应用配色。按用途命名：同一个色值只定义一次，页面里不要再写同值的新常量。

// ── 品牌橙：按钮、选中态、标题强调 ─────────────────────────────────
val BrandOrange = Color(0xFFE97F0F)
val BrandOrangeMuted = Color(0xCCE97F0F)  // 80% 不透明，OOBE 的按钮和图标底色
val BrandOrangeFaint = Color(0x66E97F0F)  // 40% 不透明，只用于 Material 描边
val OnBrandOrange = Color.White

// ── OOBE（首次引导）页面：底色更亮一档，卡片是灰色块 ───────────────────
val OobeBackground = Color(0xFF121212)
val OobeSurface = Color(0xFF2B2B2B)
val OobeSurfaceVariant = Color(0xFF1E1E1E)
val OobeTextPrimary = Color(0xFFFAFAFA)
val OobeTextSecondary = Color(0x80FFFFFF)  // 50% 白
val OobeTextDimmed = Color(0x66FFFFFF)     // 40% 白

// ── 主页各页（WebUI、消息平台、设置、终端）：深邃基底 + 温润扁平卡片 ──────────
val PageBackground = Color(0xFF0B0C0E)
val TextPrimary = Color.White      // 主要文字、图标
val CardSurface = Color(0xFF1A1B1F)
val CardBorder = Color(0xFF262830)           // 次级控件槽底、输入框柔边
val CardSurfaceSelected = Color(0xFF30333C)  // 卡片里选中的一格（接入方式选择器）
val CardDivider = Color(0x12FFFFFF)          // 7% 纯白行间细丝分隔线
val TextMuted = Color(0xFF8E929B)  // 次要说明文字
val TextFaint = Color(0xFF6B6F78)  // 更弱一级的提示文字

// ── 扁平开关：取消粗描边，用柔灰与品牌橙区分开关态 ──────────────────────
val SwitchTrackUnchecked = Color(0xFF32353E)
val SwitchThumbUnchecked = Color(0xFFE2E4EA)

// ── 清新扁平功能图标色板：明快不浑浊的实色圆形徽章底 ─────────────────────
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

// ── 状态色 ───────────────────────────────────────────────────────
val StatusOnline = Color(0xFF30D158)   // 在线、已开启、已是最新
val StatusWarning = Color(0xFFE8A064)  // 离线、未授权、启动失败
val StatusDanger = Color(0xFFFF453A)   // 危险操作按钮（退出登录等）

// ── 毛玻璃：悬浮导航栏、刷新按钮、授权弹窗、启动页信息卡 ──────────────────
val GlassSurface = Color(0x8C222328)
val GlassStroke = Color(0x1FFFFFFF)             // 12% 柔和微光描边，避免刺眼塑料边
val SubtleGlassSurface = Color(0x332B2B2B)
val GlassButtonSurface = Color(0x552B2B2B)      // 圆形小按钮、启动页信息卡的半透明底
val GlassSelectedIndicator = Color(0x33FFFFFF)  // 悬浮底栏里选中标签的滑块
const val SubtleGlassBlurRadius = 6f

// ── 终端输出 ─────────────────────────────────────────────────────
val TerminalBackground = Color(0xFF0B0B0C)  // 日志区底色，比卡片更黑一档
val TerminalText = Color(0xFFD0D0D4)        // 没有颜色序列的日志文字

// ── 唤醒页（开机自检画面）：墨黑底 + 三档灰字，强调色沿用品牌橙 ──────────────
val BootInk = Color(0xFF0A0B0D)        // 底色，也是收尾时上下拉开的两扇快门
val BootTrack = Color(0xFF22262B)      // 进度条轨道
val BootText = Color(0xFFECECE7)       // 抬头
val BootTextDim = Color(0xFF9A9EA5)    // 自检行
val BootTextFaint = Color(0xFF5C6168)  // 百分比、SKIP
val BootAccent = BrandOrange           // 进度条、LINK 行、收尾时的中缝和快门边线

// 启动页的插画按这个尺寸（dp）的设计稿绘制，运行时按实际屏幕等比缩放。
const val DesignFrameWidthDp = 412f
const val DesignFrameHeightDp = 917f
