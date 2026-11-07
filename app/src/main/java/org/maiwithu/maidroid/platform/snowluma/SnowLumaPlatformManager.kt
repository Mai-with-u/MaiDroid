package org.maiwithu.maidroid.platform.snowluma

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.platform.qq.SnowLumaAdapterInstaller
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.NetworkMonitor
import org.maiwithu.maidroid.runtime.NetworkStatus
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.runtime.StorageSpace
import org.maiwithu.maidroid.runtime.flushFileSystems
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * 消息平台页“QQ（SnowLuma）”这一路的业务：安装 SnowLuma、从 NapCat 切过来、把 QQ 窗口显示给用户并替用户点、重启 QQ，
 * 并把这些状况汇总成 SnowLumaPlatformState 给界面。
 *
 * 创建后每 2 秒从文件、OneBot 端口和 SnowLuma 管理页接口刷新一次状态；QQ 窗口弹窗打开期间每秒读一次 Xvfb 的屏幕文件。
 * 进程启停由 SnowLumaProcessSupervisor 负责，和 NapCat 二选一由 QqClientSwitcher 负责。
 */
internal class SnowLumaPlatformManager(
    private val appContext: Context,
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner,
    private val snowLumaProcess: SnowLumaProcessSupervisor,
    private val adapterInstaller: SnowLumaAdapterInstaller,
    private val switcher: QqClientSwitcher,
    private val network: NetworkMonitor
) {
    private companion object {
        const val STATUS_REFRESH_INTERVAL_MS = 2_000L
        const val SCREEN_REFRESH_INTERVAL_MS = 1_000L
        const val INSTALL_PROGRESS_POLL_INTERVAL_MS = 500L
        const val ONE_BOT_CONNECT_TIMEOUT_MS = 250

        // 断网失败后最多等网络恢复这么久再自动重装；网络刚恢复时先等几秒让 DNS 就绪
        const val OFFLINE_RETRY_WAIT_MS = 30 * 60_000L
        const val NETWORK_SETTLE_MS = 3_000L

        // 要从网上下载的两步：SnowLuma 和 LinuxQQ、MaiBot 侧的 QQ 适配器
        val DOWNLOAD_STEPS = setOf(FailureStep.SnowLumaInstall, FailureStep.SnowLumaAdapter)
        val AccountConfigName = Regex("""onebot_(\d+)\.json""")
    }

    private val files = SnowLumaFiles(paths)
    private val webUiClient = SnowLumaWebUiClient(files)
    private val scope = backgroundScope("QQ（SnowLuma）", Dispatchers.Main.immediate)
    private val random = SecureRandom()
    private var installJob: Job? = null
    private var progressJob: Job? = null
    private var autoRetryJob: Job? = null

    // 往 QQ 窗口里点击一次要起一次容器命令，连点时排队依次点，不同时起好几个
    private val clickMutex = Mutex()

    private val _state = MutableStateFlow(
        SnowLumaPlatformState.initial(installed = files.isInstalled(), active = switcher.activeClient() == QqClient.SnowLuma)
    )
    val state: StateFlow<SnowLumaPlatformState> = _state.asStateFlow()

    private val _screen = MutableStateFlow<QqScreenFrame?>(null)

    /** QQ 窗口的画面，只在 watchScreen 运行期间（QQ 窗口弹窗开着）更新。 */
    val screen: StateFlow<QqScreenFrame?> = _screen.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refreshStatus()
                delay(STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    /** 立即重读一次状态（打开页面、点刷新时用）。 */
    fun refresh() {
        scope.launch { refreshStatus() }
    }

    /** 已登录时点设置要打开的 SnowLuma 管理页地址，带密码让页面自动登录。 */
    fun webUiPageUrl(): String = webUiClient.pageUrl()

    /** 直接问一次 QQ 是否已登录；SnowLuma 没起来时按未登录处理。 */
    suspend fun isQqLoggedIn(): Boolean {
        if (_state.value.oneBotReachable) return true
        val processes = webUiClient.processes() ?: return false
        return snowLumaHookPhase(processes) == SnowLumaHookPhase.LoggedIn
    }

    /**
     * QQ 窗口弹窗打开期间运行，弹窗关闭时随调用方协程一起取消：每秒读一次 Xvfb 的屏幕文件，
     * 画面变了才更新 screen。弹窗关了就清掉画面，下次打开重新读。
     */
    suspend fun watchScreen() {
        val reader = QqScreenReader(files.screenFile)
        try {
            while (currentCoroutineContext().isActive) {
                val frame = withContext(Dispatchers.IO) { reader.read() }
                if (frame?.signature != _screen.value?.signature) _screen.value = frame
                delay(SCREEN_REFRESH_INTERVAL_MS)
            }
        } finally {
            _screen.value = null
        }
    }

    /**
     * 用户点了 QQ 窗口画面上的 (fractionX, fractionY)：换成屏幕坐标 -> 在容器里用 xdotool 点一下。
     * 扫码页的“刷新二维码”、快速登录的“登录”按钮都靠它；点完下一次读屏就能看到变化。
     */
    fun clickQqWindow(frame: QqScreenFrame, fractionX: Float, fractionY: Float) {
        val (x, y) = frame.screenPoint(fractionX, fractionY)
        scope.launch {
            clickMutex.withLock {
                val display = withContext(Dispatchers.IO) { files.readDisplay() } ?: return@withLock
                val result = commandRunner.run(SnowLumaScripts.click(display, x, y))
                if (!result.success) TerminalLog.append("[SnowLuma] 点击 QQ 窗口失败: ${result.summary()}")
            }
        }
    }

    /** 重启 QQ（和 SnowLuma）：QQ 下线后重新起来，在 QQ 窗口里重新登录或换号。不动 MaiBot。 */
    fun restartQq() {
        if (_state.value.qqRestarting) return
        scope.launch {
            _state.update { it.copy(qqRestarting = true) }
            TerminalLog.append("[SnowLuma] 重启 QQ：QQ 下线后重新启动，之后在 QQ 窗口里登录")
            snowLumaProcess.restart()
            refreshStatus()
            _state.update { it.copy(qqRestarting = false) }
        }
    }

    /** SnowLuma 已经装好、现在用的是 NapCat 时，改用 SnowLuma：停掉 NapCat（等它的 QQ 下线）-> 拉起 SnowLuma 并重启 MaiBot。 */
    fun activate() {
        if (_state.value.switching || installJob?.isActive == true) return
        scope.launch {
            _state.update { it.copy(switching = true) }
            switcher.activate(QqClient.SnowLuma)
            restartRuntime()
            refreshStatus()
            _state.update { it.copy(switching = false) }
        }
    }

    /**
     * 用户在协议弹窗里点了“同意并安装”。已经装好就只改用 SnowLuma；否则在后台：
     * 检查容器、MaiBot 源码和版本、网络和空间 -> SnowLuma 本体没装才跑安装脚本（进度文件每 500 毫秒同步到进度环）->
     * 没装就装 MaiBot 侧的 SnowLuma 适配器 -> 改用 SnowLuma（停掉 NapCat）-> 经前台服务拉起 SnowLuma 并重启 MaiBot ->
     * 等适配器配置生成后迁移连接设置。和 NapCat 的安装共用一把锁（两边都会跑 apt、装 QQ）。
     */
    fun installSnowLuma() {
        if (installJob?.isActive == true) return
        if (files.isInstalled() && !adapterInstaller.needsInstall()) {
            activate()
            return
        }
        cancelAutoRetry()
        installJob = scope.launch {
            if (!switcher.installLock.tryLock()) {
                fail(Failure(FailureStep.SnowLumaPrecheck, "NapCat 正在安装，等它装完再装 SnowLuma"))
                return@launch
            }
            // 意外异常（读写文件出错、系统拒绝启动服务……）也落到失败提示上，不让进度环一直转、按钮点不了
            try {
                runInstall()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(Failure(FailureStep.SnowLumaInstall, "安装时出了意外错误：${e.message ?: e.javaClass.simpleName}"))
            } finally {
                switcher.installLock.unlock()
            }
        }
    }

    private suspend fun runInstall() {
        _state.update {
            it.copy(
                phase = SnowLumaInstallPhase.Installing,
                installed = false,
                progress = 0.02f,
                progressDescription = "准备安装 SnowLuma",
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
            fail(Failure(FailureStep.SnowLumaPrecheck, validationError))
            return
        }
        // SnowLuma、LinuxQQ 和 QQ 适配器都要从网上下载，没连网、空间明摆着不够时直接说
        val precheck = NetworkStatus.read(appContext).offlineFailure(FailureStep.SnowLumaInstall)
            ?: installSpaceShortage()
        if (precheck != null) {
            fail(precheck)
            return
        }

        TerminalLog.append("")
        TerminalLog.append("[SnowLuma] 启动安装流程（SnowLuma ${SnowLumaScripts.VERSION}，LinuxQQ ${SnowLumaScripts.QQ_VERSION}）")

        if (!withContext(Dispatchers.IO) { files.isSnowLumaReady() }) {
            clearInstallProgress()
            startProgressWatcher()
            val result = commandRunner.run(
                SnowLumaScripts.install(token = randomHex(32), webUiPassword = randomHex(16)),
                onLine = TerminalLog::append
            )
            TerminalLog.append("[SnowLuma] 安装流程退出码: ${result.exitCode}")
            progressJob?.cancel()
            progressJob = null
            if (!result.success) {
                fail(failureOf(FailureStep.SnowLumaInstall, result.summary(), result.output))
                return
            }
            // 刚装下的几百 MB（QQ、SnowLuma）落到存储上再往下走，这时断电也不会留下“标记在、文件空”
            withContext(Dispatchers.IO) { flushFileSystems() }
        }

        if (withContext(Dispatchers.IO) { adapterInstaller.needsInstall() }) {
            _state.update { it.copy(progress = 0.92f, progressDescription = "下载 MaiBot 的 QQ 适配器") }
            val adapterOutput = StringBuilder()
            val adapterError = adapterInstaller.install(QqClient.SnowLuma, onLine = { line ->
                TerminalLog.append(line)
                adapterOutput.appendLine(line)
            })
            if (adapterError != null) {
                fail(failureOf(FailureStep.SnowLumaAdapter, adapterError, adapterOutput.toString() + adapterError))
                return
            }
            withContext(Dispatchers.IO) { flushFileSystems() }
        }

        // 改用 SnowLuma（之前用 NapCat 就先等它的 QQ 下线）-> 重启 MaiBot 加载适配器、生成配置（同时拉起 SnowLuma）->
        // 生成后迁移连接设置。适配器以前为 NapCat 迁移过就不用再迁：两边的地址、端口和 token 一样。
        _state.update { it.copy(progress = 0.95f, progressDescription = "等麦麦生成 QQ 适配器配置") }
        switcher.activate(QqClient.SnowLuma)
        TerminalLog.append("[SnowLuma] 重启麦麦加载 QQ 适配器，SnowLuma 和 QQ 随之启动")
        restartRuntime()
        val migrationError = adapterInstaller.migrateConfigWhenReady()
        if (migrationError != null) {
            fail(Failure(FailureStep.SnowLumaAdapterConfig, migrationError))
            return
        }

        if (withContext(Dispatchers.IO) { files.isInstalled() }) {
            _state.update {
                it.copy(
                    phase = SnowLumaInstallPhase.Installed,
                    installed = true,
                    active = true,
                    progress = 1f,
                    progressDescription = "SnowLuma 安装和自动配置完成",
                    failure = null
                )
            }
            TerminalLog.append("[SnowLuma] 安装完成：在 QQ 窗口里扫码登录后，麦麦就能收发 QQ 消息")
        } else {
            fail(Failure(FailureStep.SnowLumaFilesCheck, "SnowLuma 或 QQ 适配器的文件不完整，请重试"))
        }
    }

    /**
     * SnowLuma 本体还没装时看空间：LinuxQQ 加 SnowLuma 再加安装包下载约 1 GB。
     * 不够时扣掉已经装着的 QQ（多半是 NapCat 装的同一份）和上次装到一半的 SnowLuma，这两个目录只在空间看着不够时才去量。
     */
    private suspend fun installSpaceShortage(): Failure? = withContext(Dispatchers.IO) {
        if (files.isSnowLumaReady()) return@withContext null
        val what = "安装 SnowLuma 和 QQ"
        StorageSpace.shortageFailure(FailureStep.SnowLumaInstall, paths.appFilesDir, StorageSpace.SNOWLUMA_INSTALL_BYTES, what)
            ?: return@withContext null
        val installed = StorageSpace.sizeOf(files.qqDir) + StorageSpace.sizeOf(files.appDir)
        StorageSpace.shortageFailure(
            FailureStep.SnowLumaInstall, paths.appFilesDir, StorageSpace.SNOWLUMA_INSTALL_BYTES - installed, what
        )
    }

    private fun cancelAutoRetry() {
        autoRetryJob?.cancel()
        autoRetryJob = null
    }

    // 经前台服务重启运行时：被启用的协议端没在跑就会被拉起，MaiBot 重启后加载适配器。
    private fun restartRuntime() {
        MaiBotRuntimeService.start(appContext, MaiBotRuntimeService.ACTION_RESTART_RUNTIME)
    }

    private fun clearInstallProgress() {
        runCatching { files.installProgressFile.delete() }
        runCatching { files.installProgressDescriptionFile.delete() }
    }

    // 安装脚本在进度文件里写 0~1 的进度和当前步骤，每 500 毫秒同步到卡片的进度环上。
    private fun startProgressWatcher() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val progress = withContext(Dispatchers.IO) {
                    files.installProgressFile.takeIf { it.isFile }?.readText()?.trim()?.toFloatOrNull()
                }
                val description = withContext(Dispatchers.IO) {
                    files.installProgressDescriptionFile.takeIf { it.isFile }?.readText()?.trim().orEmpty()
                }
                if (progress != null) {
                    _state.update {
                        it.copy(
                            phase = SnowLumaInstallPhase.Installing,
                            progress = progress.coerceIn(0f, 1f),
                            progressDescription = description.ifBlank { it.progressDescription }
                        )
                    }
                }
                delay(INSTALL_PROGRESS_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 刷新一次状态：现在启用的是不是 SnowLuma -> 装没装好 -> 启用时才探测 OneBot 端口、问 SnowLuma 的进程列表
     * （启用 NapCat 时 8095 是 NapCat 开的，不能算到 SnowLuma 头上）-> 账号优先取 SnowLuma 汇报的，
     * 取不到再看按 QQ 号另存的 onebot_<QQ号>.json。
     */
    private suspend fun refreshStatus() {
        val snapshot = withContext(Dispatchers.IO) {
            val active = switcher.activeClient() == QqClient.SnowLuma
            Triple(active, files.isInstalled(), active && isOneBotReachable())
        }
        val (active, installed, reachable) = snapshot
        val running = snowLumaProcess.isRunning
        val processes = if (active && running) webUiClient.processes() else null
        val primary = processes?.let(::primaryQqProcess)
        val accountFromConfig = withContext(Dispatchers.IO) { newestAccountConfigUin() }
        _state.update { current ->
            current.copy(
                phase = when {
                    current.isInstalling -> SnowLumaInstallPhase.Installing
                    installed -> SnowLumaInstallPhase.Installed
                    current.phase == SnowLumaInstallPhase.Failed -> SnowLumaInstallPhase.Failed
                    else -> SnowLumaInstallPhase.Idle
                },
                progress = if (installed && !current.isInstalling) 1f else current.progress,
                progressDescription = if (installed && !current.isInstalling) "SnowLuma 已安装" else current.progressDescription,
                installed = installed,
                active = active,
                running = running,
                oneBotReachable = reachable,
                hookPhase = processes?.let(::snowLumaHookPhase),
                hookError = primary?.error.orEmpty(),
                accountId = primary?.uin?.ifBlank { null } ?: accountFromConfig,
                accountName = primary?.nickname?.ifBlank { null },
                // 账号只看 SnowLuma 这次认出来的，不看按 QQ 号另存的旧配置（以前登录过也会有）
                accountDetected = !primary?.uin.isNullOrBlank()
            )
        }
    }

    // OneBot 服务端只有 QQ 登录后才会监听，所以“端口能连上”等价于“QQ 在线且 MaiBot 能连”。
    private fun isOneBotReachable(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(QqOneBot.HOST, QqOneBot.PORT), ONE_BOT_CONNECT_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    // QQ 登录后 SnowLuma 按 QQ 号另存 onebot_<QQ号>.json；最近改过的那个多半是当前账号，只在问不到 SnowLuma 时用。
    private fun newestAccountConfigUin(): String? =
        files.configDir.listFiles()
            ?.filter { it.isFile && AccountConfigName.matches(it.name) }
            ?.maxByOrNull(File::lastModified)
            ?.let { AccountConfigName.matchEntire(it.name)?.groupValues?.getOrNull(1) }

    /**
     * 安装失败：消息平台页显示说明、错误码和“反馈问题”，错误码同时写进终端日志。
     * 要下载的两步失败时手机已经断网，就按断网报；断网的失败等手机重新连上网（最多 30 分钟）再从头装一次。
     */
    private fun fail(reported: Failure) {
        progressJob?.cancel()
        progressJob = null
        val offline = reported.step in DOWNLOAD_STEPS && reported.cause != FailureCause.NoNetwork &&
            !NetworkStatus.read(appContext).connected
        val failure = if (offline) reported.copy(cause = FailureCause.NoNetwork) else reported
        val waitForNetwork = failure.cause == FailureCause.NoNetwork
        _state.update {
            it.copy(
                phase = SnowLumaInstallPhase.Failed,
                installed = files.isInstalled(),
                progress = 0f,
                progressDescription = if (waitForNetwork) "SnowLuma 安装失败，连上网后会自动重试" else "SnowLuma 安装失败",
                failure = failure
            )
        }
        TerminalLog.append("[SnowLuma] 安装失败: ${failure.message}")
        TerminalLog.append(failure.logLine())
        if (waitForNetwork) {
            autoRetryJob = scope.launch {
                withTimeoutOrNull(OFFLINE_RETRY_WAIT_MS) { network.connected.first { it } } ?: return@launch
                delay(NETWORK_SETTLE_MS)
                TerminalLog.append("$AUTO_FIX_LOG_PREFIX 网络恢复了，自动重新安装 SnowLuma")
                autoRetryJob = null
                installSnowLuma()
            }
        }
    }

    // OneBot token（只在第一次装 QQ 协议端时用）和 WebUI 密码：随机字节的十六进制。
    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
