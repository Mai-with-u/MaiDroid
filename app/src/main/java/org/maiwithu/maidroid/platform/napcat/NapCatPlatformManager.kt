package org.maiwithu.maidroid.platform.napcat

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.diagnostics.AUTO_FIX_LOG_PREFIX
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.diagnostics.failureOf
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqClientSwitcher
import org.maiwithu.maidroid.platform.qq.SnowLumaAdapterInstaller
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.NetworkMonitor
import org.maiwithu.maidroid.runtime.NetworkStatus
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.runtime.StorageSpace
import org.maiwithu.maidroid.runtime.flushFileSystems
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
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
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner,
    private val napCatProcess: NapCatProcessSupervisor,
    private val adapterInstaller: SnowLumaAdapterInstaller,
    private val switcher: QqClientSwitcher,
    private val network: NetworkMonitor
) {
    private companion object {
        const val STATUS_REFRESH_INTERVAL_MS = 2_000L
        const val LOGIN_STATUS_POLL_INTERVAL_MS = 3_000L
        const val INSTALL_PROGRESS_POLL_INTERVAL_MS = 500L

        // 断网失败后最多等网络恢复这么久再自动重装；网络刚恢复时先等几秒让 DNS 就绪
        const val OFFLINE_RETRY_WAIT_MS = 30 * 60_000L
        const val NETWORK_SETTLE_MS = 3_000L

        // 要从网上下载的两步：NapCat 本体（安装器、LinuxQQ）和 MaiBot 侧的 QQ 适配器
        val DOWNLOAD_STEPS = setOf(FailureStep.NapCatInstall, FailureStep.NapCatAdapter)
    }

    // 断网失败后排着的自动重装；用户自己点安装时取消
    private var autoRetryJob: Job? = null

    private val files = NapCatFiles(paths)
    private val statusReader = NapCatStatusReader(files)
    private val webUiClient = NapCatWebUiClient(files.webUiConfig)
    private val scope = backgroundScope("QQ（NapCat）", Dispatchers.Main.immediate)
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
     * 点 QQ 卡片的下载按钮（选中 NapCat 适配器时）。已经装好就改用 NapCat 并重启运行时；否则在后台：
     * 检查容器、MaiBot 源码和 MaiBot 版本 -> NapCat 本体没装才跑 NapCat 安装脚本（进度文件每 500 毫秒同步到进度环）->
     * 没装就装 MaiBot 侧的 SnowLuma 适配器（SnowLumaAdapterInstaller，顺带删掉停更的旧适配器）->
     * 改用 NapCat（停掉 SnowLuma）-> 经前台服务拉起 NapCat 并重启 MaiBot，让它加载适配器、生成默认配置 ->
     * 等配置生成后迁移连接设置，MaiBot 发现配置变了会自己热启用适配器。
     * 和 SnowLuma 的安装共用一把锁（两边都会跑 apt、装 QQ），SnowLuma 正在装时直接告诉用户等它装完。
     */
    fun installNapCat() {
        if (installJob?.isActive == true) return

        if (files.isInstalled() && !adapterInstaller.needsInstall()) {
            scope.launch {
                switcher.activate(QqClient.NapCat)
                refreshStatus()
                restartRuntime()
            }
            return
        }

        cancelAutoRetry()
        installJob = scope.launch {
            if (!switcher.installLock.tryLock()) {
                fail(Failure(FailureStep.NapCatPrecheck, "SnowLuma 正在安装，等它装完再装 NapCat"))
                return@launch
            }
            // 意外异常（读写文件出错、系统拒绝启动服务……）也落到失败提示上，不让进度环一直转、按钮点不了
            try {
                runInstall()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(Failure(FailureStep.NapCatInstall, "安装时出了意外错误：${e.message ?: e.javaClass.simpleName}"))
            } finally {
                switcher.installLock.unlock()
            }
        }
    }

    private suspend fun runInstall() {
        _state.update {
            it.copy(
                phase = NapCatInstallPhase.Installing,
                installed = false,
                progress = 0.02f,
                progressDescription = "准备安装 NapCat",
                failure = null
            )
        }

        val validationError = withContext(Dispatchers.IO) {
            readiness.containerProblem()
                ?: if (!readiness.isMaiBotSourceReady()) {
                    "MaiBot 源码尚未安装，无法配置 QQ 适配器"
                } else {
                    adapterInstaller.maiBotVersionProblem()
                }
        }
        if (validationError != null) {
            fail(Failure(FailureStep.NapCatPrecheck, validationError))
            return
        }
        // NapCat 安装器、LinuxQQ 和 QQ 适配器都要从网上下载，没连网、空间明摆着不够时直接说
        val precheck = NetworkStatus.read(appContext).offlineFailure(FailureStep.NapCatInstall)
            ?: installSpaceShortage()
        if (precheck != null) {
            fail(precheck)
            return
        }

        TerminalLog.append("")
        TerminalLog.append("[NapCat] 启动安装流程")

        if (!withContext(Dispatchers.IO) { files.isNapCatReady() }) {
            statusReader.clearInstallProgress()
            startProgressWatcher()
            val result = runInstallScript(NapCatScripts.install(token = generateToken()))
            progressJob?.cancel()
            progressJob = null
            if (!result.success) {
                fail(failureOf(FailureStep.NapCatInstall, result.summary(), result.output))
                return
            }
            // 刚装下的几百 MB（QQ、NapCat）落到存储上再往下走，这时断电也不会留下“标记在、文件空”的 QQ
            withContext(Dispatchers.IO) { flushFileSystems() }
        }

        if (withContext(Dispatchers.IO) { adapterInstaller.needsInstall() }) {
            _state.update { it.copy(progress = 0.92f, progressDescription = "下载 MaiBot 的 QQ 适配器") }
            // 安装输出照常进终端，同时留一份用来认失败原因
            val adapterOutput = StringBuilder()
            val adapterError = adapterInstaller.install(QqClient.NapCat, onLine = { line ->
                TerminalLog.append(line)
                adapterOutput.appendLine(line)
            })
            if (adapterError != null) {
                fail(failureOf(FailureStep.NapCatAdapter, adapterError, adapterOutput.toString() + adapterError))
                return
            }
            withContext(Dispatchers.IO) { flushFileSystems() }
        }

        // 改用 NapCat（之前启用的是 SnowLuma 就先等它的 QQ 下线）-> 重启 MaiBot 让它加载新适配器、
        // 按适配器默认值生成配置（同时拉起 NapCat），生成后再迁移连接设置。
        _state.update { it.copy(progress = 0.95f, progressDescription = "等麦麦生成 QQ 适配器配置") }
        switcher.activate(QqClient.NapCat)
        TerminalLog.append("[NapCat] 重启麦麦加载 QQ 适配器，等它生成配置后迁移连接设置")
        restartRuntime()
        val migrationError = adapterInstaller.migrateConfigWhenReady()
        if (migrationError != null) {
            fail(Failure(FailureStep.NapCatAdapterConfig, migrationError))
            return
        }

        if (withContext(Dispatchers.IO) { files.isInstalled() }) {
            val snapshot = withContext(Dispatchers.IO) { statusReader.read(checkConnection = false) }
            _state.update {
                it.copy(
                    phase = NapCatInstallPhase.Installed,
                    installed = true,
                    progress = 1f,
                    progressDescription = "NapCat 安装和自动配置完成",
                    failure = null,
                    qrCodePath = snapshot.qrCodePath,
                    qrCodeLastModified = snapshot.qrCodeLastModified
                )
            }
            TerminalLog.append("[NapCat] 安装完成，麦麦会按迁移好的配置自动启用 QQ 适配器")
        } else {
            fail(Failure(FailureStep.NapCatFilesCheck, "NapCat 或 QQ 适配器的文件不完整，请重试"))
        }
    }

    /**
     * NapCat 本体还没装时看空间：LinuxQQ 加 NapCat 再加安装包下载约 1 GB。
     * 不够时扣掉上次装到一半已经装上的 QQ 和 NapCat（它们这次不用再占新空间），这两个目录只在空间看着不够时才去量。
     */
    private suspend fun installSpaceShortage(): Failure? = withContext(Dispatchers.IO) {
        if (files.isNapCatReady()) return@withContext null
        val what = "安装 NapCat 和 QQ"
        StorageSpace.shortageFailure(FailureStep.NapCatInstall, paths.appFilesDir, StorageSpace.NAPCAT_INSTALL_BYTES, what)
            ?: return@withContext null
        val installed = StorageSpace.sizeOf(File(paths.rootfsDir, "opt/QQ")) + StorageSpace.sizeOf(files.napCatDir)
        StorageSpace.shortageFailure(
            FailureStep.NapCatInstall, paths.appFilesDir, StorageSpace.NAPCAT_INSTALL_BYTES - installed, what
        )
    }

    private fun cancelAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = null
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

    /**
     * 安装失败：消息平台页显示说明、错误码和“反馈问题”，错误码同时写进终端日志。
     * 要下载的两步失败时手机已经断网，就按断网（NET-OFFLINE）报，不让镜像超时这些表象把人带偏；
     * 断网的失败排一次自动重装：等手机重新连上网（最多 30 分钟）再从头装，装过的部分安装脚本会自己跳过。
     */
    private fun fail(reported: Failure) {
        // 进度环的轮询停下来，不然它会把阶段又改回“安装中”
        progressJob?.cancel()
        progressJob = null
        val offline = reported.step in DOWNLOAD_STEPS && reported.cause != FailureCause.NoNetwork &&
            !NetworkStatus.read(appContext).connected
        val failure = if (offline) reported.copy(cause = FailureCause.NoNetwork) else reported
        val waitForNetwork = failure.cause == FailureCause.NoNetwork
        _state.update {
            it.copy(
                phase = NapCatInstallPhase.Failed,
                installed = files.isInstalled(),
                progress = 0f,
                progressDescription = if (waitForNetwork) "NapCat 安装失败，连上网后会自动重试" else "NapCat 安装失败",
                failure = failure
            )
        }
        TerminalLog.append("[NapCat] 安装失败: ${failure.message}")
        TerminalLog.append(failure.logLine())
        if (waitForNetwork) {
            autoRetryJob = scope.launch {
                withTimeoutOrNull(OFFLINE_RETRY_WAIT_MS) { network.connected.first { it } } ?: return@launch
                delay(NETWORK_SETTLE_MS)
                TerminalLog.append("$AUTO_FIX_LOG_PREFIX 网络恢复了，自动重新安装 NapCat")
                autoRetryJob = null
                installNapCat()
            }
        }
    }

    // OneBot WebSocket 的访问 token：32 字节随机数的十六进制，只在第一次安装时使用。
    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
