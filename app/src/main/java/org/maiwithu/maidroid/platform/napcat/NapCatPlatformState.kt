package org.maiwithu.maidroid.platform.napcat

/** NapCat 的安装进度阶段。 */
internal enum class NapCatInstallPhase {
    Idle,
    Installing,
    Installed,
    Failed
}

/**
 * 消息平台页里 NapCat 相关的全部界面状态：安装进度、登录二维码、OneBot 连通性、当前 QQ 账号、扫码进度。
 * 由 NapCatPlatformManager 每 2 秒从文件和端口刷新一次，二维码弹窗打开时再额外轮询扫码进度。
 */
internal data class NapCatPlatformState(
    val phase: NapCatInstallPhase,
    val installed: Boolean,
    val progress: Float,
    val progressDescription: String,
    val errorMessage: String?,
    val qrCodePath: String?,
    val qrCodeLastModified: Long,
    val oneBotReachable: Boolean,
    val oneBotConfig: NapCatOneBotConfig?,
    val accountId: String?,
    val accountName: String?,
    // 以下三项只在二维码弹窗打开期间由 keepQrCodeFresh / refreshQrCode 维护。
    val loginPhase: NapCatLoginPhase? = null,
    val qrRefreshing: Boolean = false,
    val qrRefreshError: String? = null,
    // 用户点了“退出 QQ 登录”，正在清自动登录并重启 NapCat。
    val qqLoggingOut: Boolean = false
) {
    val isInstalling: Boolean = phase == NapCatInstallPhase.Installing
    val hasQrCode: Boolean = qrCodePath != null && qrCodeLastModified > 0L

    /** 装好了、或者已经出过登录二维码，卡片上的按钮就从“下载”变成“设置”。 */
    val canOpenSettings: Boolean = installed || hasQrCode
    val accountDisplayName: String? = accountName?.ifBlank { null } ?: accountId

    // 扫码登录已完成：弹窗轮询到 NapCat 汇报 ready，或 OneBot 端口已经能连上（登录后才会监听）。
    val qrLoginCompleted: Boolean = loginPhase == NapCatLoginPhase.Ready || oneBotReachable

    companion object {
        fun initial(installed: Boolean, qrCodePath: String?, qrCodeLastModified: Long) =
            NapCatPlatformState(
                phase = if (installed) NapCatInstallPhase.Installed else NapCatInstallPhase.Idle,
                installed = installed,
                progress = if (installed) 1f else 0f,
                progressDescription = if (installed) "NapCat 已安装" else "等待安装",
                errorMessage = null,
                qrCodePath = qrCodePath,
                qrCodeLastModified = qrCodeLastModified,
                oneBotReachable = false,
                oneBotConfig = null,
                accountId = null,
                accountName = null
            )
    }
}

/** 从 onebot11*.json 里读出的 WebSocket 服务端配置，NapCat 配置弹窗逐项展示。 */
internal data class NapCatOneBotConfig(
    val serviceType: String,
    val name: String,
    val enabled: Boolean,
    val host: String,
    val port: Int?,
    val token: String,
    val messagePostFormat: String,
    val reportSelfMessage: Boolean?,
    val enableForcePushEvent: Boolean?,
    val debug: Boolean?,
    val heartInterval: Int?,
    /** 容器内路径，例如 /root/napcat/config/onebot11_123456.json。 */
    val configPath: String
)
