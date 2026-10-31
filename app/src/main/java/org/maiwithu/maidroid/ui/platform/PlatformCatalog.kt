package org.maiwithu.maidroid.ui.platform

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.platform.qqofficial.qqOfficialStatusText
import org.maiwithu.maidroid.ui.theme.StatusOnline
import org.maiwithu.maidroid.ui.theme.TextMuted

/**
 * 平台卡片下方选择器里的接入方式，label 是选择器上显示的文字。目前只有 QQ 有多种接入方式。
 * 接入方式说的是“消息从哪条路进出 QQ”，和 MaiBot 的适配器插件不是一回事：每种接入方式各自用哪个适配器写在下面。
 */
internal enum class AccessMethod(val label: String) {
    /** QQ 开放平台的官方机器人，MaiBot 侧用 MaiBot-QQ-Adapter 直连，代码在 platform/qqofficial。 */
    QqOfficial("官方机器人"),

    /** 容器里跑 NapCat 登录个人 QQ，MaiBot 侧用 MaiBot-SnowLuma-Adapter 去连，代码在 platform/napcat。 */
    NapCat("NapCat"),

    /** 在手机上跑 SnowLuma 客户端代替 NapCat，还没适配，只是展示；和 NapCat 用的 SnowLuma 适配器不是一回事。 */
    SnowLumaClient("SnowLuma")
}

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
    val accessMethods: List<AccessMethod> = emptyList(),
    // 选中某个适配器时卡片上的服务方说明换成这里的文字，没列出的适配器沿用 provider。
    val accessMethodProviders: Map<AccessMethod, String> = emptyMap()
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
        // 官方机器人放最左并默认选中（见 MessagePlatformsPage 的 selectedAccessMethods 初值）。
        accessMethods = listOf(AccessMethod.QqOfficial, AccessMethod.NapCat, AccessMethod.SnowLumaClient),
        accessMethodProviders = mapOf(
            AccessMethod.NapCat to "将由 NapCat 提供服务",
            AccessMethod.SnowLumaClient to "SnowLuma 暂未适配"
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
        provider = "暂未适配",
        description = "海外主流的即时通讯应用",
        tags = listOf("海外", "常用")
    ),
    AvailablePlatform(
        name = "Signal",
        iconRes = R.drawable.ic_platform_signal,
        iconColor = Color(0xCC3A76F0),
        provider = "暂未适配",
        description = "注重隐私的加密通讯应用",
        tags = listOf("海外", "常用")
    )
)

/**
 * “已安装”一行显示哪些卡片：按 NapCat 和 QQ 官方机器人的真实状态生成，没装的不显示。
 * 设置页“消息平台”一行的运行中数量也按这里的 running 统计，两处口径一致。
 */
internal fun installedPlatformCards(
    napCatState: NapCatPlatformState,
    qqOfficialState: QqOfficialPlatformState
): List<InstalledPlatform> = listOfNotNull(
    napCatInstalledCard(napCatState),
    qqOfficialInstalledCard(qqOfficialState)
)

private fun napCatInstalledCard(napCatState: NapCatPlatformState): InstalledPlatform? {
    if (!napCatState.installed && !napCatState.hasQrCode && napCatState.oneBotConfig == null) {
        return null
    }

    val account = when {
        napCatState.oneBotReachable -> "${napCatState.accountDisplayName ?: "QQ"} 在线"
        napCatState.installed -> "NapCat 等待连接"
        else -> "NapCat 待配置"
    }

    return InstalledPlatform(
        kind = InstalledPlatformKind.NapCat,
        name = "QQ（NapCat）",
        iconRes = R.drawable.ic_platform_qq,
        account = account,
        iconColor = Color(0xCC0F3BE9),
        statusColor = if (napCatState.oneBotReachable) StatusOnline else TextMuted,
        running = napCatState.oneBotReachable
    )
}

/**
 * 官方机器人装好后才出现在已安装行里。适配器连上 QQ 时不上报，
 * 所以“已启用”只表示凭据齐全、MaiBot 在跑且近一分钟没有连接报错。
 */
private fun qqOfficialInstalledCard(state: QqOfficialPlatformState): InstalledPlatform? {
    if (!state.installed) return null
    return InstalledPlatform(
        kind = InstalledPlatformKind.QqOfficial,
        // 卡片标题只有 130dp 宽，“QQ（官方机器人）”会折行压住状态行，所以不加括号。
        name = "QQ 官方机器人",
        iconRes = R.drawable.ic_platform_qq,
        account = qqOfficialStatusText(state),
        iconColor = Color(0xCC0F3BE9),
        statusColor = qqOfficialStatusColor(state),
        running = state.presumedOnline
    )
}
