package org.maiwithu.maidroid.platform

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.log.TerminalLog
import org.maiwithu.maidroid.platform.napcat.NapCatFiles
import org.maiwithu.maidroid.platform.napcat.NapCatInstallPhase
import org.maiwithu.maidroid.platform.napcat.NapCatLoginAccount
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.napcat.NapCatProcessSupervisor
import org.maiwithu.maidroid.platform.napcat.NapCatScripts
import org.maiwithu.maidroid.platform.napcat.NapCatStatusReader
import org.maiwithu.maidroid.platform.napcat.NapCatWebUiClient
import org.maiwithu.maidroid.platform.napcat.shouldAutoRefreshQrCode
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import java.security.SecureRandom

/**
 * 消息平台页“QQ（NapCat）”这一路的业务：安装 NapCat、展示和刷新登录二维码、退出 QQ 登录，
 * 并把这些状况汇总成 NapCatPlatformState 给界面。
 *
 * 创建后每 2 秒从文件和 OneBot 端口刷新一次状态（NapCatStatusReader），
 * 只有二维码弹窗打开期间才通过 NapCat 自带 WebUI 的接口（NapCatWebUiClient）轮询扫码进度。
 * 进程启停由 NapCatProcessSupervisor 负责。
 */
internal class NapCatPlatformManager(
    private val appContext: Context,
    paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner,
    private val napCatProcess: NapCatProcessSupervisor
) {
    private companion object {
        const val STATUS_REFRESH_INTERVAL_MS = 2_000L
        const val LOGIN_STATUS_POLL_INTERVAL_MS = 3_000L
        const val INSTALL_PROGRESS_POLL_INTERVAL_MS = 500L
    }

    private val files = NapCatFiles(paths)
    private val statusReader = NapCatStatusReader(files)
    private val webUiClient = NapCatWebUiClient(files.webUiConfig)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val random = SecureRandom()
    private var installJob: Job? = null
    private var progressJob: Job? = null
    private var lastQrRefreshAttemptAt = 0L

    // 当前登录的 QQ 账号，OneBot 端口连通后向 NapCat 查一次，端口断开即清空；只在主线程读写。
    private var loginAccount: NapCatLoginAccount? = null

    private val _state = MutableStateFlow(readInitialState())
    val state: StateFlow<NapCatPlatformState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refreshStatus()
                delay(STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    /** 立即重读一次状态（打开页面、点刷新时用），不必等下一轮定时刷新。 */
    fun refresh() {
        scope.launch {
            refreshStatus()
        }
    }

    /** 已登录时点 NapCat 设置要打开的管理页地址，带 token 让页面自动登录。 */
    fun webUiPageUrl(): String = webUiClient.pageUrl()

    /** 直接问 NapCat QQ 是否已登录；NapCat 没起来或 WebUI 不通时按未登录处理。 */
    suspend fun isQqLoggedIn(): Boolean = webUiClient.loginStatus()?.isLogin == true

    /**
     * 退出 QQ 登录。NapCat 没有注销 QQ 的接口，QQ 登录态只随 QQ 进程结束而断开，所以：
     * 清掉 NapCat 的自动登录账号（否则重启后又自动快速登录回去）-> 删掉旧二维码图 ->
     * NapCat 在跑就重启它，QQ 随之下线；之后打开二维码弹窗会自动要一张新码，可以换号扫码。
     */
    fun logoutQq() {
        if (_state.value.qqLoggingOut) return
        scope.launch {
            _state.update { it.copy(qqLoggingOut = true) }
            TerminalLog.append("[NapCat] 退出 QQ 登录：清除自动登录账号并重启 NapCat")
            webUiClient.clearAutoLoginAccount().exceptionOrNull()?.let { e ->
                TerminalLog.append("[NapCat] 清除自动登录账号失败: ${e.message.orEmpty()}")
            }
            withContext(Dispatchers.IO) { files.qrCodeFile.delete() }
            loginAccount = null
            if (napCatProcess.isRunning) {
                napCatProcess.restart()
            }
            refreshStatus()
            _state.update { it.copy(qqLoggingOut = false) }
        }
    }

    /** 用户在二维码弹窗点“刷新”：让 NapCat 重新向 QQ 要一张新码，而不是只重读磁盘上的旧图。 */
    fun refreshQrCode() {
        scope.launch { requestNewQrCode() }
    }

    /**
     * 二维码弹窗打开期间运行，弹窗关闭时随调用方协程一起取消。
     * 每 3 秒：向 NapCat 问扫码进度 -> 同步到弹窗提示 -> 码已失效或扫码作废就自动换新码；
     * 新码由 NapCat 写回 qrcode.png，再经 2 秒一次的状态刷新显示出来。
     */
    suspend fun keepQrCodeFresh() {
        try {
            while (currentCoroutineContext().isActive) {
                val status = webUiClient.loginStatus()
                if (status != null) {
                    _state.update { it.copy(loginPhase = status.phase) }
                    // 码龄直接看文件当前的修改时间：状态里的时间戳最多晚 2 秒，
                    // NapCat 刚自己换了码时会被误判成旧码过期而再换一次。
                    val qrModifiedAt = withContext(Dispatchers.IO) { files.qrCodeFile.lastModified() }
                    val now = System.currentTimeMillis()
                    val qrAgeMs = now - qrModifiedAt
                    if (shouldAutoRefreshQrCode(status, qrAgeMs, now - lastQrRefreshAttemptAt)) {
                        TerminalLog.append("[NapCat] 登录二维码已失效，自动请求新二维码")
                        // 刷新放到管理器自己的作用域里跑：用户中途关弹窗也能跑完并复位 qrRefreshing。
                        refreshQrCode()
                    }
                }
                delay(LOGIN_STATUS_POLL_INTERVAL_MS)
            }
        } finally {
            // 弹窗关了就不再展示扫码进度，下次打开重新查。
            _state.update { it.copy(loginPhase = null, qrRefreshError = null) }
        }
    }

    // 手动刷新和自动换码共用：同一时刻只发一个刷新请求，结果写回弹窗状态。
    // 全程跑在主线程调度器上，判断和置位 qrRefreshing 之间没有挂起点，不会并发重入。
    private suspend fun requestNewQrCode() {
        if (_state.value.qrRefreshing) return
        lastQrRefreshAttemptAt = System.currentTimeMillis()
        _state.update { it.copy(qrRefreshing = true, qrRefreshError = null) }
        val result = webUiClient.refreshQrCode()
        refreshStatus()
        val error = result.exceptionOrNull()
        if (error != null) {
            TerminalLog.append("[NapCat] 刷新二维码失败: ${error.message.orEmpty()}")
        }
        _state.update {
            it.copy(
                qrRefreshing = false,
                qrRefreshError = error?.let { e -> "刷新二维码失败：${e.message.orEmpty()}" }
            )
        }
    }

    /**
     * 点 QQ 卡片的下载按钮（选中 NapCat 适配器时）。已经装好就只重启运行时；否则在后台：
     * 检查容器和 MaiBot 源码 -> 跑安装脚本（进度文件每 500 毫秒同步到进度环）->
     * 装好后经前台服务启动 NapCat 并重启 MaiBot 加载适配器。
     */
    fun installNapCat() {
        if (installJob?.isActive == true) return

        if (files.isInstalled()) {
            refresh()
            restartRuntime()
            return
        }

        installJob = scope.launch {
            _state.update {
                it.copy(
                    phase = NapCatInstallPhase.Installing,
                    installed = false,
                    progress = 0.02f,
                    progressDescription = "准备安装 NapCat",
                    errorMessage = null
                )
            }

            val validationError = withContext(Dispatchers.IO) {
                readiness.containerProblem()
                    ?: if (!readiness.isMaiBotSourceReady()) {
                        "MaiBot 源码尚未安装，无法配置 NapCat 适配器"
                    } else {
                        null
                    }
            }
            if (validationError != null) {
                fail(validationError)
                return@launch
            }

            statusReader.clearInstallProgress()
            startProgressWatcher()

            TerminalLog.append("")
            TerminalLog.append("[NapCat] 启动安装流程")

            val result = runInstallScript(NapCatScripts.install(token = generateToken()))
            progressJob?.cancel()
            progressJob = null

            if (result.success && files.isInstalled()) {
                val snapshot = withContext(Dispatchers.IO) { statusReader.read(checkConnection = false) }
                _state.update {
                    it.copy(
                        phase = NapCatInstallPhase.Installed,
                        installed = true,
                        progress = 1f,
                        progressDescription = "NapCat 安装和自动配置完成",
                        errorMessage = null,
                        qrCodePath = snapshot.qrCodePath,
                        qrCodeLastModified = snapshot.qrCodeLastModified
                    )
                }
                TerminalLog.append("[NapCat] 安装完成，正在启动 NapCat 并重启 MaiBot 以加载适配器")
                restartRuntime()
            } else {
                fail(result.summary())
            }
        }
    }

    // NapCat 装好或已存在时，经前台服务重启运行时：NapCat 若未运行会被拉起，MaiBot 重启后加载适配器。
    private fun restartRuntime() {
        MaiBotRuntimeService.start(appContext, MaiBotRuntimeService.ACTION_RESTART_RUNTIME)
    }

    // 安装脚本的输出原样写进终端日志，最后补一行退出码，方便判断失败在哪一步。
    private suspend fun runInstallScript(script: ContainerScript): ContainerCommandResult {
        val result = commandRunner.run(script, onLine = TerminalLog::append)
        TerminalLog.append("[NapCat] 安装流程退出码: ${result.exitCode}")
        return result
    }

    private fun startProgressWatcher() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val fallback = _state.value.progress
                val progress = withContext(Dispatchers.IO) { statusReader.readInstallProgress(fallback) }
                if (progress != null) {
                    _state.update {
                        it.copy(
                            phase = NapCatInstallPhase.Installing,
                            progress = progress.value,
                            progressDescription = progress.description.ifBlank {
                                it.progressDescription
                            }
                        )
                    }
                }
                delay(INSTALL_PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshStatus() {
        var snapshot = withContext(Dispatchers.IO) { statusReader.read(preferredUin = loginAccount?.uin) }
        // OneBot 端口通了说明 QQ 已登录：第一次连通时问 NapCat 登的是哪个号，按它挑 onebot11_<QQ号>.json
        // 并显示昵称（换过号后最新修改的配置文件不一定是当前账号）；端口断了就忘掉，下次登录可能换号。
        if (!snapshot.oneBotReachable) {
            loginAccount = null
        } else if (loginAccount == null) {
            webUiClient.loginAccount()?.let { account ->
                loginAccount = account
                snapshot = withContext(Dispatchers.IO) { statusReader.read(preferredUin = account.uin) }
            }
        }
        val account = loginAccount
        _state.update { current ->
            current.copy(
                phase = when {
                    current.isInstalling -> NapCatInstallPhase.Installing
                    snapshot.installed -> NapCatInstallPhase.Installed
                    current.phase == NapCatInstallPhase.Failed -> NapCatInstallPhase.Failed
                    else -> NapCatInstallPhase.Idle
                },
                progress = if (snapshot.installed && !current.isInstalling) 1f else current.progress,
                progressDescription = if (snapshot.installed && !current.isInstalling) {
                    "NapCat 已安装"
                } else {
                    current.progressDescription
                },
                qrCodePath = snapshot.qrCodePath,
                qrCodeLastModified = snapshot.qrCodeLastModified,
                installed = snapshot.installed,
                oneBotReachable = snapshot.oneBotReachable,
                oneBotConfig = snapshot.oneBotConfig,
                accountId = account?.uin ?: snapshot.accountId,
                accountName = account?.nick
            )
        }
    }

    // 创建时在调用线程同步读一次文件（不探测端口），让界面第一帧就有安装状态。
    private fun readInitialState(): NapCatPlatformState {
        val snapshot = statusReader.read(checkConnection = false)
        return NapCatPlatformState.initial(
            installed = snapshot.installed,
            qrCodePath = snapshot.qrCodePath,
            qrCodeLastModified = snapshot.qrCodeLastModified
        ).copy(
            oneBotConfig = snapshot.oneBotConfig,
            accountId = snapshot.accountId
        )
    }

    private fun fail(message: String) {
        _state.update {
            it.copy(
                phase = NapCatInstallPhase.Failed,
                installed = files.isInstalled(),
                progress = 0f,
                progressDescription = "NapCat 安装失败",
                errorMessage = message
            )
        }
        TerminalLog.append("[NapCat] 安装失败: $message")
    }

    // OneBot WebSocket 的访问 token：32 字节随机数的十六进制，只在第一次安装时使用。
    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
