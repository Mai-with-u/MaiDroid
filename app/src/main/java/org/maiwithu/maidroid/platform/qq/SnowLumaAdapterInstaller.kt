package org.maiwithu.maidroid.platform.qq

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.maibot.PluginHostVersionRange
import org.maiwithu.maidroid.maibot.maiBotRecentRunOutput
import org.maiwithu.maidroid.maibot.pluginHostVersionProblem
import org.maiwithu.maidroid.maibot.readPluginHostVersionRange
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 装 MaiBot 侧的 SnowLuma 适配器（MaiBot-SnowLuma-Adapter）并把它的配置迁移成连本机的 QQ 协议端（QqOneBot）。
 * 首次安装 NapCat、SnowLuma（两边的 PlatformManager）和启动协议端前的换装、补迁移（QqAdapterUpkeep）共用
 * AppDependencies 里的同一个实例，迁移用 Single-flight 合并并发等待，几处同时触发时复用同一轮结果。
 *
 * 顺序：读出旧适配器的配置留在内存里 -> 如尚未安装新适配器则先克隆到暂存目录 ->
 * 从新适配器自身的 `_manifest.json` 动态校验与当前 MaiBot 版本的兼容性 ->
 * 兼容才移入正式目录并清理旧适配器（不兼容则清理暂存目录、保留旧适配器原样不动）->
 * 调用方重启 MaiBot -> migrateConfigWhenReady 等 MaiBot 按适配器默认值生成 config.toml ->
 * 在上面启用插件、写连接项、搬旧设置。
 */
internal class SnowLumaAdapterInstaller(
    private val files: QqAdapterFiles,
    private val commandRunner: ContainerCommandRunner
) {
    private companion object {
        // 等 MaiBot 生成配置：重启、补装依赖、加载插件在手机上可能要几分钟。
        const val CONFIG_WAIT_TIMEOUT_MS = 5 * 60_000L
        const val CONFIG_POLL_INTERVAL_MS = 2_000L

        // 看到生成的配置后再等一会儿：MaiBot 写完配置后才订阅它的变化，太早改会错过热重载。
        const val CONFIG_SETTLE_DELAY_MS = 5_000L
    }

    private val migrationStateLock = Any()
    private var activeMigration: CompletableDeferred<String?>? = null

    // 换装时从旧适配器读出的配置，迁移成功后清掉；应用中途被杀就丢了，只是少搬几项旧设置。
    @Volatile
    private var pendingLegacyConfig: String? = null

    /** 新适配器还没装，或者旧适配器还在（会和新的同时连协议端）时需要跑一遍 install。 */
    fun needsInstall(): Boolean = files.adapterDir() == null || files.legacyAdapterDir() != null

    /** 新适配器装好了，但配置还没迁移（MaiBot 还没生成，或连接项还没指向本机的协议端）。 */
    fun needsConfigMigration(): Boolean = files.adapterDir() != null && !files.isAdapterConfigured()

    /**
     * 核对指定（默认已安装）QQ 适配器目录的 `_manifest.json` 与当前 MaiBot 版本是否兼容。
     * 还没装好或读不到版本范围时返回 null，真正下载后再按暂存目录的清单复检。
     */
    fun maiBotVersionProblem(pluginDir: File? = files.adapterDir()): String? {
        if (pluginDir == null) return null
        val required = readPluginHostVersionRange(pluginDir) ?: return null
        val version = files.readMaiBotVersion()
        return adapterHostVersionProblem(version, required)
    }

    /**
     * 备份旧适配器配置 -> 按需克隆新适配器到暂存目录 -> 校验清单兼容后扶正并删掉旧适配器。
     * 若新适配器与当前 MaiBot 版本不兼容，直接丢弃暂存目录、不动原有旧适配器，避免打断现有可用环境。
     */
    suspend fun install(client: QqClient, onLine: (String) -> Unit): String? {
        val needsClone = withContext(Dispatchers.IO) {
            files.legacyAdapterDir()
                ?.let { File(it, "config.toml") }
                ?.takeIf { it.isFile }
                ?.let { legacy -> runCatching { legacy.readText() }.getOrNull() }
                ?.let { pendingLegacyConfig = it }
            files.adapterDir() == null
        }

        if (needsClone) {
            val result = commandRunner.run(SnowLumaAdapterScripts.cloneToStaging(client.logPrefix), onLine = onLine)
            if (!result.success) {
                withContext(Dispatchers.IO) { deleteTreeSafely(files.stagingAdapterDir) }
                return "下载 QQ 适配器失败：${result.summary()}"
            }
            val stageOutcome = withContext(Dispatchers.IO) {
                val staging = files.stagingAdapterDir
                val complete = staging.isDirectory &&
                    File(staging, "_manifest.json").isFile &&
                    File(staging, "plugin.py").isFile
                if (!complete) {
                    deleteTreeSafely(staging)
                    return@withContext "QQ 适配器目录不完整"
                }
                maiBotVersionProblem(staging)?.let { problem ->
                    deleteTreeSafely(staging)
                    return@withContext problem
                }
                moveStagingToTarget(staging, files.targetAdapterDir)
            }
            if (stageOutcome != null) {
                onLine("${client.logPrefix} $stageOutcome")
                return stageOutcome
            }
            onLine("${client.logPrefix} QQ 适配器已下载到 ${InContainerPaths.MAIBOT_PLUGINS_DIR}/${SnowLumaAdapterScripts.DIR_NAME}")
        } else {
            val existingProblem = withContext(Dispatchers.IO) { maiBotVersionProblem(files.adapterDir()) }
            if (existingProblem != null) {
                onLine("${client.logPrefix} $existingProblem")
                return existingProblem
            }
            onLine("${client.logPrefix} QQ 适配器已存在，跳过下载")
        }

        val removedLegacyNames = withContext(Dispatchers.IO) {
            if (files.adapterDir() == null) return@withContext null
            files.legacyAdapterDirs().map { legacyDir ->
                deleteTreeSafely(legacyDir)
                legacyDir.name
            }
        } ?: return "QQ 适配器目录不完整"

        for (legacyName in removedLegacyNames) {
            onLine("${client.logPrefix} 删除已停更的旧 NapCat 适配器: ${InContainerPaths.MAIBOT_PLUGINS_DIR}/$legacyName")
        }
        return null
    }

    /**
     * 等 MaiBot 生成适配器的 config.toml（带 plugin.config_version）后迁移：每 2 秒看一次，最多等 5 分钟；
     * 看到后再等 5 秒让 MaiBot 订阅好配置变化，然后启用插件、写连接项、搬旧设置。
     * 并发调用共享同一轮等待（Single-flight），避免安装页和进程监控各自排队等待两轮。
     */
    suspend fun migrateConfigWhenReady(onWaiting: () -> Unit = {}): String? {
        var isLeader = false
        val deferred = synchronized(migrationStateLock) {
            val current = activeMigration
            if (current != null && current.isActive) {
                current
            } else {
                CompletableDeferred<String?>().also {
                    activeMigration = it
                    isLeader = true
                }
            }
        }
        if (!isLeader) {
            return deferred.await()
        }
        return try {
            val result = withContext(Dispatchers.IO) { waitAndMigrate(onWaiting) }
            deferred.complete(result)
            result
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            synchronized(migrationStateLock) {
                if (activeMigration === deferred) {
                    activeMigration = null
                }
            }
        }
    }

    private suspend fun waitAndMigrate(onWaiting: () -> Unit): String? {
        // 已装入的适配器本身要求更高版本的 MaiBot 时，不必白等 5 分钟再超时。
        maiBotVersionProblem()?.let { return it }
        val token = files.readOneBotToken() ?: return "找不到 OneBot token，请重新安装 NapCat 或 SnowLuma"
        var waited = 0L
        while (true) {
            if (files.adapterDir() == null) return "找不到 QQ 适配器目录"
            val config = files.adapterConfig()
            val text = config.takeIf { it.isFile }?.readText()
            if (text != null && SnowLumaAdapterConfig.isMigrated(text, token)) {
                pendingLegacyConfig = null
                return null
            }
            if (text != null && SnowLumaAdapterConfig.isGenerated(text)) {
                delay(CONFIG_SETTLE_DELAY_MS)
                return runCatching {
                    rewriteAdapterConfig(config) { current ->
                        SnowLumaAdapterConfig.migrate(current ?: text, token, pendingLegacyConfig)
                    }
                    pendingLegacyConfig = null
                }.exceptionOrNull()?.let {
                    "写入 QQ 适配器配置失败：${it.message.orEmpty().ifBlank { it.javaClass.simpleName }}"
                }
            }
            if (waited >= CONFIG_WAIT_TIMEOUT_MS) {
                val recentLines = maiBotRecentRunOutput(TerminalLog.logs.value.map(AnsiText::plain)).lines()
                return diagnoseAdapterConfigWaitFailure(recentLines)
            }
            if (waited == 0L) onWaiting()
            delay(CONFIG_POLL_INTERVAL_MS)
            waited += CONFIG_POLL_INTERVAL_MS
        }
    }
}

private fun moveStagingToTarget(staging: File, target: File): String? {
    deleteTreeSafely(target)
    if (staging.renameTo(target)) return null
    return runCatching {
        Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        null
    }.getOrElse { error ->
        "安装 QQ 适配器目录失败：${error.message.orEmpty().ifBlank { error.javaClass.simpleName }}"
    }
}

// 不跟随符号链接删除目录树，避免清理时顺着软链接误删外部目录。
private fun deleteTreeSafely(file: File) {
    if (Files.isSymbolicLink(file.toPath())) {
        file.delete()
        return
    }
    if (!file.exists()) return
    if (file.isDirectory) {
        file.listFiles()?.forEach(::deleteTreeSafely)
    }
    file.delete()
}

/**
 * MaiBot 版本超出适配器 `_manifest.json` 声明的兼容范围时返回提示；
 * 版本号或清单范围缺失、无法解析时不拦，交由 MaiBot 自己判断。
 */
internal fun adapterHostVersionProblem(
    maiBotVersion: String?,
    required: PluginHostVersionRange?
): String? = pluginHostVersionProblem("QQ 适配器", maiBotVersion, required)

/**
 * 5 分钟内没等到适配器生成 `config.toml` 时，从 MaiBot 最近运行日志里认出是否因版本不兼容或依赖受阻而拒绝加载；
 * 认不出具体加载失败时退回通用提示。
 */
internal fun diagnoseAdapterConfigWaitFailure(recentMaiBotLines: List<String>): String {
    val contextLines = buildList {
        recentMaiBotLines.forEachIndexed { idx, line ->
            val mentionsAdapter = line.contains(SnowLumaAdapterScripts.PLUGIN_ID, ignoreCase = true) ||
                line.contains(SnowLumaAdapterScripts.DIR_NAME, ignoreCase = true) ||
                line.contains("silk-python", ignoreCase = true)
            if (mentionsAdapter) {
                add(line)
                recentMaiBotLines.getOrNull(idx + 1)?.let(::add)
                recentMaiBotLines.getOrNull(idx + 2)?.let(::add)
            }
        }
    }
    if (contextLines.any { it.contains("低于最小要求") }) {
        return "MaiBot 拒绝加载 QQ 适配器（当前版本低于最小要求），请先在设置页“MaiBot 更新”里更新 MaiBot 后重试"
    }
    if (contextLines.any { it.contains("manifest 校验失败") || it.contains("Host 版本不兼容") }) {
        return "MaiBot 拒绝加载 QQ 适配器（manifest 校验失败），请更新 MaiBot 或 QQ 适配器后重试"
    }
    if (contextLines.any { it.contains("依赖流水线阻止加载") || it.contains("silk-python") }) {
        return "MaiBot 安装 QQ 适配器依赖失败（silk-python），请检查网络并查看终端输出后重试"
    }
    return "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试"
}
