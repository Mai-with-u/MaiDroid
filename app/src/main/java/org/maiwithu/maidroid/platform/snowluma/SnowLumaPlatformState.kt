package org.maiwithu.maidroid.platform.snowluma

import org.maiwithu.maidroid.diagnostics.Failure

/** SnowLuma 的安装进度阶段。 */
internal enum class SnowLumaInstallPhase {
    Idle,
    Installing,
    Installed,
    Failed
}

/**
 * 消息平台页里 SnowLuma 相关的全部界面状态：安装进度、是否启用、QQ 登录进度、OneBot 连通性、当前 QQ 账号。
 * 由 SnowLumaPlatformManager 每 2 秒从文件、端口和 SnowLuma 的管理页接口刷新一次。
 */
internal data class SnowLumaPlatformState(
    val phase: SnowLumaInstallPhase,
    val installed: Boolean,
    /** 现在启用的 QQ 协议端是 SnowLuma（和 NapCat 二选一）。 */
    val active: Boolean,
    val progress: Float,
    val progressDescription: String,
    /** 安装失败时带错误码的原因。 */
    val failure: Failure?,
    /** SnowLuma 和 QQ 的进程在跑（或正在启动）。 */
    val running: Boolean = false,
    /** OneBot 端口能连上：QQ 登录后 SnowLuma 才开始监听，所以等于“QQ 在线、MaiBot 能连”。只在启用 SnowLuma 时探测。 */
    val oneBotReachable: Boolean = false,
    /** SnowLuma 汇报的登录进度；管理页接口不通（没在跑、还在启动）时为 null。 */
    val hookPhase: SnowLumaHookPhase? = null,
    /** 注入失败时 SnowLuma 给的原因。 */
    val hookError: String = "",
    val accountId: String? = null,
    val accountName: String? = null,
    /**
     * SnowLuma 认出了 QQ 正在用的账号：QQ 已经登录、打开了这个号的数据，只是 SnowLuma 还没确认接管好。
     * 真机上看到它只在 QQ 真正登录后才出现（停在登录页时认不出），所以这时不该再叫用户去登录。
     */
    val accountDetected: Boolean = false,
    /** 用户点了“重启 QQ”，正在停下再拉起。 */
    val qqRestarting: Boolean = false,
    /** 正在从 NapCat 切到 SnowLuma（等 NapCat 的 QQ 下线）。 */
    val switching: Boolean = false
) {
    val isInstalling: Boolean = phase == SnowLumaInstallPhase.Installing
    val errorMessage: String? get() = failure?.message

    /** 装好了，卡片上的按钮就从“下载”变成“设置”。 */
    val canOpenSettings: Boolean = installed
    val accountDisplayName: String? = accountName?.ifBlank { null } ?: accountId

    /** QQ 已登录：OneBot 端口已经开了，或者 SnowLuma 汇报已登录。 */
    val loggedIn: Boolean = oneBotReachable || hookPhase == SnowLumaHookPhase.LoggedIn

    companion object {
        fun initial(installed: Boolean, active: Boolean) = SnowLumaPlatformState(
            phase = if (installed) SnowLumaInstallPhase.Installed else SnowLumaInstallPhase.Idle,
            installed = installed,
            active = active,
            progress = if (installed) 1f else 0f,
            progressDescription = if (installed) "SnowLuma 已安装" else "等待安装",
            failure = null
        )
    }
}

/** QQ 窗口弹窗里的登录进度提示；SnowLuma 没汇报（没在跑、还在启动）时按进程是否在跑给一句。 */
internal fun snowLumaLoginHint(state: SnowLumaPlatformState): String = when {
    state.qqRestarting -> "正在重启 QQ…"
    state.loggedIn -> "QQ 已登录"
    // QQ 登录了、SnowLuma 还没接管好：说清楚不是用户的事，一直这样就去看终端
    state.hookPhase == SnowLumaHookPhase.WaitingLogin && state.accountDetected ->
        "QQ 已登录，正在等 SnowLuma 接管；一直停在这里，原因见终端里 [SnowLuma] 开头的输出"
    state.hookPhase == SnowLumaHookPhase.WaitingLogin -> "用手机 QQ 扫码登录；二维码过期了就点一下二维码刷新，也可以点“登录”用上次的账号"
    state.hookPhase == SnowLumaHookPhase.Injecting -> "SnowLuma 正在连接 QQ…"
    state.hookPhase == SnowLumaHookPhase.QqStarting -> "QQ 正在启动…"
    state.hookPhase == SnowLumaHookPhase.Disconnected -> "SnowLuma 和 QQ 断开了，可以在设置里重启 QQ"
    state.hookPhase == SnowLumaHookPhase.Failed ->
        "SnowLuma 没能接入 QQ：${state.hookError.ifBlank { "原因见终端里 [SnowLuma] 开头的输出" }}"
    state.running -> "SnowLuma 正在启动…"
    else -> "SnowLuma 没有在运行，可以在设置里重启 QQ"
}
