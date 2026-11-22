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
 * - 预留语音合成自动部署、Amaidesu 联动等外部组件扩展位，后续新增能力只需补充条目并挂载对应控制卡片。
 */
internal object ExtensionCatalog {
    val primaryEntry = ExtensionEntry(
        id = ExtensionId.DesktopPet,
        title = "MaiBot 悬浮桌宠",
        subtitle = "在其他应用上层显示可拖拽交互的麦麦悬浮桌宠，自动复用本机 WebUI 身份并跟随容器同步前端资源。",
        badge = "本机集成",
        available = true
    )

    val upcomingEntries: List<ExtensionEntry> = listOf(
        ExtensionEntry(
            id = ExtensionId.TtsAutoDeploy,
            title = "TTS 自动部署",
            subtitle = "面向麦麦语音合成引擎的一键部署、服务启停与音色参数管理入口。",
            badge = "规划中",
            available = false
        ),
        ExtensionEntry(
            id = ExtensionId.AmaidesuBridge,
            title = "Amaidesu 等麦系组件",
            subtitle = "预留直播交互与其他外部麦系软件的桥接能力，无需再调整首页导航架构即可平铺扩展。",
            badge = "即将支持",
            available = false
        )
    )
}
