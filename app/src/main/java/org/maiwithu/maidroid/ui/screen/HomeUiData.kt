package org.maiwithu.maidroid.ui.screen

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.theme.StatusOnline

internal const val WebUiLogTag = "MaiDroidWebUi"

internal enum class MainTab {
    WebUi,
    Platforms,
    Settings
}

// QQ 卡片适配器选择器里的选项名，同时作为 selectedAdapters 里记下的值。
internal const val QqOfficialAdapterName = "官方机器人"
internal const val NapCatAdapterName = "NapCat"

// 已安装卡片点齿轮时按来源打开对应的配置弹窗。
internal enum class InstalledPlatformKind {
    NapCat,
    QqOfficial
}

internal data class InstalledPlatform(
    val kind: InstalledPlatformKind,
    val name: String,
    @param:DrawableRes val iconRes: Int,
    val account: String,
    val iconColor: Color,
    val statusColor: Color = StatusOnline,
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
    val adapters: List<String> = emptyList(),
    // 选中某个适配器时卡片上的服务方说明换成这里的文字，没列出的适配器沿用 provider。
    val adapterProviders: Map<String, String> = emptyMap()
)

// 已安装平台只由 installedPlatformCards 按 NapCat 和 QQ 官方机器人的真实状态生成，
// 这里不再放写死的假账号，避免没适配的平台被误显示成"在线"。
internal val AvailablePlatforms = listOf(
    AvailablePlatform(
        name = "QQ",
        iconRes = R.drawable.ic_platform_qq,
        iconColor = Color(0xCC0F3BE9),
        // 服务方说明和简介共两行，说明一折行就会把简介挤掉，所以每条都要短到窄卡片上也能一行放下。
        provider = "将由 QQ 官方接口提供服务",
        description = "日常闲聊，水群必选平台",
        tags = listOf("国内", "常用"),
        badge = "最佳适配",
        // 官方机器人放最左并默认选中（见 MessagePlatformsPage 的 selectedAdapters 初值）。
        adapters = listOf(QqOfficialAdapterName, NapCatAdapterName, "SnowLuma"),
        adapterProviders = mapOf(
            NapCatAdapterName to "将由 NapCat 提供服务",
            "SnowLuma" to "SnowLuma 暂未适配"
        )
    ),
    AvailablePlatform(
        name = "Discord",
        iconRes = R.drawable.ic_platform_discord,
        iconColor = Color(0xCC5865F2),
        // Discord 还没有适配器，点安装只会走"暂不支持"提示，所以不能写成已经有服务方，和 WhatsApp、Signal 保持一致。
        provider = "暂未适配",
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
