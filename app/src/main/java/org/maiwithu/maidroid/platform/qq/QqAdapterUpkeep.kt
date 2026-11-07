package org.maiwithu.maidroid.platform.qq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 每次启动 QQ 协议端（NapCat 或 SnowLuma）前对 MaiBot 侧 SnowLuma 适配器做的检查和补救，两个进程监管器共用一份。
 *
 * 老用户换装：旧版本 MaiDroid 装的是已停更的 MaiBot-Napcat-Adapter。启动协议端前检查一次：
 * 还没有新适配器或旧的还在 -> 装上新适配器、删掉旧的 -> 重启 MaiBot 让它加载新插件并生成默认配置 ->
 * 后台等配置生成后迁移（启用、连本机协议端、搬旧设置），不耽误 QQ 启动。
 * 只差配置迁移（上次等配置时应用被杀或超时）就直接在后台等着迁移。
 * MaiBot 低于新适配器要求的版本时先不动（旧适配器还能用），只提示去更新 MaiBot，更新后下次启动协议端再换。
 * 失败只记日志，不影响协议端启动；下载失败时旧适配器还在，下次启动再试。
 */
internal class QqAdapterUpkeep(
    private val files: QqAdapterFiles,
    private val adapterInstaller: SnowLumaAdapterInstaller,
    private val maiBotProcess: MaiBotProcessSupervisor
) {
    private val scope = backgroundScope("QQ 适配器维护", Dispatchers.IO)
    private val lock = Any()

    // 等 MaiBot 生成适配器配置再迁移的后台任务；不跟协议端进程一起取消，重启协议端也不会重复起一个。只在 lock 内修改。
    @Volatile
    private var configMigrationJob: Job? = null

    /** 启动 client 前调用：需要时换装适配器并重启 MaiBot，需要迁移配置就在后台等着迁移。日志带 client 的前缀。 */
    suspend fun ensureBeforeStart(client: QqClient) {
        val prefix = client.logPrefix
        val needsInstall = runCatching {
            files.maiBotPyproject.isFile && adapterInstaller.needsInstall()
        }.getOrDefault(false)
        if (needsInstall) {
            adapterInstaller.maiBotVersionProblem()?.let { problem ->
                TerminalLog.append("$prefix 暂不更换 QQ 适配器：$problem")
                return
            }
            TerminalLog.append("$prefix 更换 QQ 适配器：安装 MaiBot-SnowLuma-Adapter，删除已停更的 MaiBot-Napcat-Adapter")
            val error = try {
                adapterInstaller.install(client, onLine = TerminalLog::append)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            }
            if (error != null) {
                TerminalLog.append("$prefix 更换 QQ 适配器失败，下次启动 ${client.label} 时重试：$error")
                return
            }
            TerminalLog.append("$prefix QQ 适配器已下载，重启 MaiBot 加载新适配器并生成它的配置")
            maiBotProcess.restart()
        }
        if (runCatching { adapterInstaller.needsConfigMigration() }.getOrDefault(false)) {
            startConfigMigration(client)
        }
    }

    // 后台等 MaiBot 生成适配器配置后迁移；已经有一个在等就不再起。
    private fun startConfigMigration(client: QqClient) {
        val prefix = client.logPrefix
        synchronized(lock) {
            if (configMigrationJob?.isActive == true) return
            configMigrationJob = scope.launch {
                val error = adapterInstaller.migrateConfigWhenReady(
                    onWaiting = { TerminalLog.append("$prefix 等麦麦生成 QQ 适配器的配置，生成后自动迁移连接设置") }
                )
                if (error == null) {
                    TerminalLog.append("$prefix QQ 适配器配置已迁移：已启用并连接本机 ${client.label}，麦麦会自动加载")
                } else {
                    TerminalLog.append("$prefix 迁移 QQ 适配器配置失败，下次启动 ${client.label} 时重试：$error")
                }
            }
        }
    }
}
