package org.maiwithu.maidroid.platform.qqofficial

/** 适配器的安装进度阶段。 */
internal enum class QqOfficialInstallPhase {
    Idle,
    Installing,
    Installed,
    Failed
}

/**
 * 消息平台页里“QQ 官方机器人”这一路的界面状态：安装进度、config.toml 里的凭据、MaiBot 是否在跑、
 * 最近的连接报错。由 QqOfficialPlatformManager 根据文件、MaiBot 进程状态和终端日志汇总。
 */
internal data class QqOfficialPlatformState(
    val phase: QqOfficialInstallPhase = QqOfficialInstallPhase.Idle,
    val installed: Boolean = false,
    val progress: Float = 0f,
    val progressDescription: String = "",
    // 安装失败的原因；连接失败另记在 connectionError。
    val errorMessage: String? = null,
    val credentials: QqOfficialCredentials = QqOfficialCredentials(),
    val maiBotRunning: Boolean = false,
    // 最近一段时间内适配器报的连接错误，过期或 MaiBot 重启后清空。
    val connectionError: String? = null
) {
    val isInstalling: Boolean = phase == QqOfficialInstallPhase.Installing
    val configured: Boolean = installed && credentials.configured
    // 适配器连上网关时不打日志，只能这样近似：已启用、MaiBot 在跑、近期没有连接报错。
    val presumedOnline: Boolean = configured && maiBotRunning && connectionError == null
}

/** 已安装卡片和配置弹窗里的一句话状态。 */
internal fun qqOfficialStatusText(state: QqOfficialPlatformState): String = when {
    state.isInstalling -> "正在安装"
    !state.installed -> "未安装"
    state.credentials.appId.isBlank() || state.credentials.appSecret.isBlank() -> "待填写 AppID"
    !state.credentials.enabled -> "插件已停用"
    !state.maiBotRunning -> "等待 MaiBot 启动"
    state.connectionError != null -> "连接失败"
    else -> "已启用"
}

/** 已安装后保存凭据的结果，配置弹窗据此决定提示文字。 */
internal sealed interface QqOfficialSaveResult {
    // 凭据和启用状态都没变，没有重启 MaiBot。
    data object Unchanged : QqOfficialSaveResult
    data object Restarting : QqOfficialSaveResult
    data class Failed(val message: String) : QqOfficialSaveResult
}

// 适配器连接失败时每隔 5 秒重试，单次请求最长 20 秒，所以一分钟内没有新报错就当作已经连上。
internal const val QqOfficialErrorStaleMs = 60_000L
