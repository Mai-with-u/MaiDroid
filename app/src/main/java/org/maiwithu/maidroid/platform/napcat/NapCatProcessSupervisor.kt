package org.maiwithu.maidroid.platform.napcat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ProcessExitOutcome
import org.maiwithu.maidroid.runtime.RequestedExit
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.killProot
import org.maiwithu.maidroid.runtime.requestedExitOf
import org.maiwithu.maidroid.terminal.TerminalLog
import java.util.concurrent.TimeUnit

/**
 * 容器里的 NapCat 进程（Xvfb + 注入了 NapCat 的 LinuxQQ）。由 AppDependencies 持有唯一实例，
 * 前台服务负责启停，退出 QQ 登录时只重启它。停止时先请 QQ 正常下线退出，超时才强制清理。
 *
 * 与 MaiBot 不同，NapCat 意外退出后不自动重启：QQ 掉线后要重新扫码，自动拉起也登不回去。
 */
internal class NapCatProcessSupervisor(
    private val paths: RuntimePaths,
    private val commandRunner: ContainerCommandRunner,
    private val maiBotProcess: MaiBotProcessSupervisor,
    private val adapterInstaller: SnowLumaAdapterInstaller
) {
    private companion object {
        // 停止时等旧一轮读日志（或还在启动）的协程收尾的上限。
        const val STOP_JOIN_TIMEOUT_MS = 5_000L

        // 停止时清掉 Xvfb 后等 proot 跟着结束的上限。
        const val PROOT_EXIT_WAIT_MS = 5_000L
    }

    private val files = NapCatFiles(paths)

    // 等 MaiBot 生成适配器配置再迁移的后台任务；不跟 NapCat 进程一起取消，重启 NapCat 也不会重复起一个。
    @Volatile
    private var configMigrationJob: Job? = null
    private val scope = backgroundScope("NapCat 进程监管", Dispatchers.IO)
    private val lock = Any()

    // 停止、重启、安全退出依次执行，免得两次停止同时去请 QQ 退出、清理残留（比如连点两次退出 QQ 登录）。
    private val lifecycleMutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var job: Job? = null

    // 安全退出开始后永久拦下启动请求；退出完成时整个应用进程都会结束，不需要复位。只在 lock 内修改。
    @Volatile
    private var shuttingDown = false

    /** 进程在跑，或者正在启动（清理残留、生成 launcher 的阶段）。 */
    val isRunning: Boolean
        get() = process?.isAlive == true || job?.isActive == true

    /**
     * 没装 NapCat、已在运行或 MaiDroid 正在安全退出时什么也不做。否则在后台：
     * 需要时换装新版 SnowLuma 适配器 -> 清理残留进程 -> 重新生成 launcher.sh ->
     * 前台运行 launcher.sh，把输出加 [NapCat] 前缀写进终端日志。
     * 只看 NapCat 本体装没装好：MaiBot 太旧、暂时换不了适配器的老用户，QQ 照样能登录。
     */
    fun start() {
        synchronized(lock) {
            if (shuttingDown || isRunning) return
            if (!files.isNapCatReady()) return

            RuntimeFileModes.ensureExecutableBits(paths)
            job = scope.launch { runOnce() }
        }
    }

    /** 停掉 NapCat：请 QQ 正常下线退出，再清理剩下的 Xvfb 等进程；在后台执行。 */
    fun stop() {
        scope.launch { lifecycleMutex.withLock { stopLocked() } }
    }

    /**
     * 安全退出：之后的启动请求一律拦下 -> 照常停止（请 QQ 正常下线退出）。挂起到 NapCat 完全停下。
     */
    suspend fun shutdownForExit(): ProcessExitOutcome = withContext(Dispatchers.IO) {
        synchronized(lock) { shuttingDown = true }
        lifecycleMutex.withLock { stopLocked() }
    }

    /**
     * 只重启 NapCat（QQ 随之下线重登），不动 MaiBot。
     * 照常停止（QQ 正常下线退出，旧的读日志协程也已收尾，它的 finally 不会把新进程引用清掉）-> 重新启动；
     * 新一轮启动前会先跑清理命令，杀掉残留的 qq / Xvfb。
     */
    suspend fun restart() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked() }
        start()
    }

    /**
     * 停止、重启和安全退出共用的停止流程：
     * 在跑就在容器里请 QQ 主进程自己下线退出（实测 1~2 秒，最多 20 秒）-> 清掉 launcher.sh 放到后台的 Xvfb
     * 和其余残留，proot 随之结束 -> 兜底：取消读日志协程、强杀还没退的 proot -> 等协程收尾。
     * QQ 没找到或等到超时就按清理命令强制结束。还在启动阶段（换装适配器、清理残留、生成 launcher）时
     * QQ 还没起来，直接取消启动，并清掉启动途中可能已经起来的残留。
     */
    private suspend fun stopLocked(): ProcessExitOutcome {
        val (currentProcess, runningJob) = synchronized(lock) { process to job }
        if (currentProcess?.isAlive != true) {
            terminateProcess()
            if (runningJob?.isActive == true) {
                runMaintenance(NapCatScripts.cleanup())
                withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob.join() }
            }
            return ProcessExitOutcome.NotRunning
        }

        TerminalLog.append("[NapCat] 停止 NapCat：请 QQ 正常下线退出")
        val result = commandRunner.run(NapCatScripts.requestQqExit()) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        // 没找到 QQ：QQ 早已退出（崩溃或启动失败），只剩 Xvfb 撑着 proot，下面清理掉即可。
        val outcome = when (requestedExitOf(result)) {
            RequestedExit.Exited -> ProcessExitOutcome.Exited
            RequestedExit.NotFound -> ProcessExitOutcome.NotRunning
            RequestedExit.StillRunning -> {
                TerminalLog.append("[NapCat] QQ 没有自己退出，强制清理")
                ProcessExitOutcome.ForceStopped
            }
        }
        runMaintenance(NapCatScripts.cleanup())
        currentProcess.waitFor(PROOT_EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        terminateProcess()
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob?.join() }
        return outcome
    }

    private suspend fun runOnce() {
        upgradeAdapter()
        runMaintenance(NapCatScripts.cleanup())
        runMaintenance(NapCatScripts.prepareRuntime())

        if (!files.launcher.isFile) {
            TerminalLog.append("[NapCat] 未找到 /root/launcher.sh，暂不启动 NapCat")
            return
        }

        TerminalLog.append("")
        TerminalLog.append("[NapCat] 启动: bash /root/launcher.sh")
        val ownJob = currentCoroutineContext()[Job]
        var startedProcess: Process? = null
        try {
            val currentProcess = commandRunner.start(NapCatScripts.launch())
            startedProcess = currentProcess
            // 停止（含安全退出）可能恰好在启动途中发生：terminateProcess 在同一把锁里取消本协程，
            // 所以要么停止流程看得到这个进程、请 QQ 退出，要么这里看到本协程已被取消或正在退出，
            // 自己把刚起的进程结束掉（QQ 还没登录，没有要保存的）。
            val abandoned = synchronized(lock) {
                val stale = shuttingDown || ownJob?.isActive == false
                if (!stale) process = currentProcess
                stale
            }
            if (abandoned) {
                currentProcess.killProot()
                return
            }
            currentProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    TerminalLog.append("[NapCat] ${rawLine.trimEnd('\r')}")
                }
            }
            val exitCode = currentProcess.waitFor()
            TerminalLog.append("[NapCat] 进程退出码: $exitCode")
        } catch (e: Exception) {
            TerminalLog.append("[NapCat] 启动异常: ${e.message.orEmpty()}")
        } finally {
            // 只清自己这一轮的引用，迟迟收尾的旧一轮不能把重启后新进程的引用清掉。
            synchronized(lock) {
                if (process === startedProcess) process = null
            }
        }
    }

    /**
     * 老用户换装：旧版本 MaiDroid 装的是已停更的 MaiBot-Napcat-Adapter。每次启动 NapCat 前检查一次：
     * 还没有新适配器或旧的还在 -> 装上新适配器、删掉旧的 -> 重启 MaiBot 让它加载新插件并生成默认配置 ->
     * 后台等配置生成后迁移（启用、连本机 NapCat、搬旧设置），不耽误 QQ 启动。
     * 只差配置迁移（上次等配置时应用被杀或超时）就直接在后台等着迁移。
     * MaiBot 低于新适配器要求的版本时先不动（旧适配器还能用），只提示去更新 MaiBot，更新后下次启动 NapCat 再换。
     * 失败只记日志，不影响 NapCat 启动；下载失败时旧适配器还在，下次启动再试。
     */
    private suspend fun upgradeAdapter() {
        val needsInstall = runCatching {
            paths.maiBotPyproject.isFile && adapterInstaller.needsInstall()
        }.getOrDefault(false)
        if (needsInstall) {
            adapterInstaller.maiBotVersionProblem()?.let { problem ->
                TerminalLog.append("[NapCat] 暂不更换 QQ 适配器：$problem")
                return
            }
            TerminalLog.append("[NapCat] 更换 QQ 适配器：安装 MaiBot-SnowLuma-Adapter，删除已停更的 MaiBot-Napcat-Adapter")
            val error = try {
                adapterInstaller.install(onLine = TerminalLog::append)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            }
            if (error != null) {
                TerminalLog.append("[NapCat] 更换 QQ 适配器失败，下次启动 NapCat 时重试：$error")
                return
            }
            TerminalLog.append("[NapCat] QQ 适配器已下载，重启 MaiBot 加载新适配器并生成它的配置")
            maiBotProcess.restart()
        }
        if (runCatching { adapterInstaller.needsConfigMigration() }.getOrDefault(false)) {
            startConfigMigration()
        }
    }

    // 后台等 MaiBot 生成适配器配置后迁移；已经有一个在等就不再起。
    private fun startConfigMigration() {
        synchronized(lock) {
            if (configMigrationJob?.isActive == true) return
            configMigrationJob = scope.launch {
                val error = adapterInstaller.migrateConfigWhenReady(
                    onWaiting = { TerminalLog.append("[NapCat] 等麦麦生成 QQ 适配器的配置，生成后自动迁移连接设置") }
                )
                if (error == null) {
                    TerminalLog.append("[NapCat] QQ 适配器配置已迁移：已启用并连接本机 NapCat，麦麦会自动加载")
                } else {
                    TerminalLog.append("[NapCat] 迁移 QQ 适配器配置失败，下次启动 NapCat 时重试：$error")
                }
            }
        }
    }

    // 兜底：取消启动或读日志的协程，强杀还没退的 proot（正常停止时 QQ、Xvfb 已经退出，proot 也已结束）。阻塞调用。
    private fun terminateProcess() {
        synchronized(lock) {
            job?.cancel()
            job = null
            process?.killProot()
            process = null
        }
    }

    // 清理残留进程、生成 launcher.sh 这类维护命令：只记非空输出，失败不中断后续启动。
    private suspend fun runMaintenance(script: ContainerScript) {
        val result = commandRunner.run(script) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        if (!result.success) {
            TerminalLog.append("[NapCat] maintenance command exited with code: ${result.exitCode}")
        }
    }
}
