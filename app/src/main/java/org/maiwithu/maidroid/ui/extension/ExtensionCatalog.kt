package org.maiwithu.maidroid.ui.extension

/** 扩展组件标识：首位为本机悬浮桌宠，后续可持续接入麦麦生态扩展模块。 */
internal enum class ExtensionId {
    DesktopPet,
    TtsAutoDeploy,
    AmaidesuBridge
}

/** 扩展组件目录项元数据，用于统一描述已开放与预留接入的麦系生态组件。 */
internal data class ExtensionEntry(
    val id: ExtensionId,
    val title: String,
    val subtitle: String,
    val badge: String,
    val available: Boolean
)

/**
 * 扩展组件目录注册表：
 * - 第一栏固定承载本机悬浮桌宠运行时入口；
 * - 预留语音合成自动部署、Amaidesu 生态联动等外部组件扩展位，后续新增能力只需补充条目并挂载对应控制卡片。
 */
internal object ExtensionCatalog {
    val primaryEntry = ExtensionEntry(
        id = ExtensionId.DesktopPet,
        title = "MaiBot 悬浮桌宠",
        subtitle = "悬浮于其他应用上层的麦麦桌宠，支持自由拖拽与即时互动",
        badge = "本机集成",
        available = true
    )

    val upcomingEntries: List<ExtensionEntry> = listOf(
        ExtensionEntry(
            id = ExtensionId.TtsAutoDeploy,
            title = "语音合成（TTS）",
            subtitle = "一键部署麦麦语音合成服务，自由配置音色与播报参数",
            badge = "规划中",
            available = false
        ),
        ExtensionEntry(
            id = ExtensionId.AmaidesuBridge,
            title = "Amaidesu 生态联动",
            subtitle = "连接直播互动、多端桥接与更多麦系外部工具",
            badge = "即将支持",
            available = false
        )
    )
}
