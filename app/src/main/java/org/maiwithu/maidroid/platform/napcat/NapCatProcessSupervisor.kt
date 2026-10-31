package org.maiwithu.maidroid.platform.napcat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.terminateGracefully
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 容器里的 NapCat 进程（Xvfb + 注入了 NapCat 的 LinuxQQ）。由 AppDependencies 持有唯一实例，
 * 前台服务负责启停，退出 QQ 登录时只重启它。
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
        // 重启时等旧一轮读日志协程收尾的上限。
        const val RESTART_JOIN_TIMEOUT_MS = 5_000L
    }

    private val files = NapCatFiles(paths)

    // 等 MaiBot 生成适配器配置再迁移的后台任务；不跟 NapCat 进程一起取消，重启 NapCat 也不会重复起一个。
    @Volatile
    private var configMigrationJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var job: Job? = null

    /** 进程在跑，或者正在启动（清理残留、生成 launcher 的阶段）。 */
    val isRunning: Boolean
        get() = process?.isAlive == true || job?.isActive == true

    /**
     * 没装 NapCat 或已在运行时什么也不做。否则在后台：
     * 需要时换装新版 SnowLuma 适配器 -> 清理残留进程 -> 重新生成 launcher.sh ->
     * 前台运行 launcher.sh，把输出加 [NapCat] 前缀写进终端日志。
     * 只看 NapCat 本体装没装好：MaiBot 太旧、暂时换不了适配器的老用户，QQ 照样能登录。
     */
    fun start() {
        synchronized(lock) {
            if (isRunning) return
            if (!files.isNapCatReady()) return

            RuntimeFileModes.ensureExecutableBits(paths)
            job = scope.launch { runOnce() }
        }
    }

    /** 停掉 NapCat（QQ 随之下线），再在后台清理残留的 qq / Xvfb 进程。 */
    fun stop() {
        terminateProcess()
        scope.launch { runMaintenance(NapCatScripts.cleanup()) }
    }

    /**
     * 只重启 NapCat（QQ 随之下线重登），不动 MaiBot。
     * 停旧进程 -> 等旧的读日志协程收尾（避免它的 finally 把新进程引用清掉）-> 重新启动；
     * 新一轮启动前会先跑清理命令，杀掉残留的 qq / Xvfb。
     */
    suspend fun restart() = withContext(Dispatchers.IO) {
        val previousJob = job
        terminateProcess()
        withTimeoutOrNull(RESTART_JOIN_TIMEOUT_MS) { previousJob?.join() }
        start()
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
        try {
            val currentProcess = commandRunner.start(NapCatScripts.launch())
            process = currentProcess
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
            process = null
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

    // 取消启动协程并结束当前进程（先 SIGTERM，3 秒后强杀）。
    private fun terminateProcess() {
        synchronized(lock) {
            job?.cancel()
            job = null
            process?.terminateGracefully()
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
