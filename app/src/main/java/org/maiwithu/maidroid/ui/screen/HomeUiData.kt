package org.maiwithu.maidroid.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import org.maiwithu.maidroid.R

internal val HomeBackground = Color(0xFF07090D)
internal val HomeOrange = Color(0xFFE8921E)
internal val HomeTextBlue = Color(0xFFA0B4C8)
internal val HomeMutedBlue = Color(0xFF788CA0)
internal val HomeOnline = Color(0xFF64C88C)
internal val HomeOffline = Color(0xFFE8A064)
internal const val HomeDesignWidth = 412f
internal const val HomeDesignHeight = 917f
internal val MainSurface = Color(0xFF0F0F0F)
internal val PlatformCardSurface = Color(0xFF1C1C1E)
internal val PlatformBorder = Color(0xFF2C2C2E)
internal val PlatformTextSecondary = Color(0xFF8E8E93)
internal val PlatformTextDim = Color(0xFF717171)
internal val PlatformOnline = Color(0xFF30D158)
internal val PlatformOrange = Color(0xFFE97F0F)
internal val GlassSurface = Color(0x802B2B2B)
internal val GlassStroke = Color(0x55FFFFFF)
internal const val WebUiLogTag = "MaiDroidWebUi"

internal enum class MainTab {
    WebUi,
    Platforms,
    Settings
}

internal data class InstalledPlatform(
    val name: String,
    @param:DrawableRes val iconRes: Int,
    val account: String,
    val iconColor: Color,
    val statusColor: Color = PlatformOnline,
    val running: Boolean = true
)

internal data class AvailablePlatform(
    val name: String,
    @param:DrawableRes val iconRes: Int,
    val iconColor: Color,
    val provider: String,
    val description: String,
    val tags: List<String>,
    val badge: String? = null,
    val adapters: List<String> = emptyList()
)

internal val InstalledPlatforms = listOf(
    InstalledPlatform(
        name = "微信",
        iconRes = R.drawable.ic_platform_wechat,
        account = "野兽先辈 在线",
        iconColor = Color(0xFF07C160)
    ),
    InstalledPlatform(
        name = "Telegram",
        iconRes = R.drawable.ic_platform_telegram,
        account = "Harry Poorter 在线",
        iconColor = Color(0xFF229ED9)
    ),
    InstalledPlatform(
        name = "iMessage",
        iconRes = R.drawable.ic_platform_imessage,
        account = "325799 在线",
        iconColor = Color(0xFF06C755),
        statusColor = PlatformTextSecondary
    )
)

internal val AvailablePlatforms = listOf(
    AvailablePlatform(
        name = "QQ",
        iconRes = R.drawable.ic_platform_qq,
        iconColor = Color(0xCC0F3BE9),
        provider = "将由 NapCat 提供服务",
        description = "日常闲聊，水群必选平台",
        tags = listOf("国内", "常用"),
        badge = "最佳适配",
        adapters = listOf("NapCat", "SnowLuma")
    ),
    AvailablePlatform(
        name = "Discord",
        iconRes = R.drawable.ic_platform_discord,
        iconColor = Color(0xCC5865F2),
        provider = "将由 Discord 官方 API 提供服务",
        description = "游戏玩家必选平台",
        tags = listOf("海外", "游戏平台", "常用")
    ),
    AvailablePlatform(
        name = "WhatsApp",
        iconRes = R.drawable.ic_platform_whatsapp,
        iconColor = Color(0xCC25D366),
        provider = "将由 ??? 提供服务",
        description = "实则根本没有做适配",
        tags = listOf("海外", "常用")
    ),
    AvailablePlatform(
        name = "Signal",
        iconRes = R.drawable.ic_platform_signal,
        iconColor = Color(0xCC3A76F0),
        provider = "将由 ??? 提供服务",
        description = "实则根本没有做适配",
        tags = listOf("海外", "常用")
    )
)
