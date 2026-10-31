package org.maiwithu.maidroid.platform.napcat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.VersionNumber
import org.maiwithu.maidroid.maibot.maiBotProjectVersion
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import java.io.File

/**
 * 装 MaiBot 侧的 QQ 适配器（MaiBot-SnowLuma-Adapter）并把它的配置迁移成连本机 NapCat。
 * 首次安装 NapCat（NapCatPlatformManager）和老用户启动 NapCat 前的换装（NapCatProcessSupervisor）共用
 * AppDependencies 里的同一个实例，迁移用锁串行，两边同时触发也只迁移一次。
 *
 * 顺序：读出旧适配器的配置留在内存里 -> 跑安装脚本（克隆新适配器、删旧适配器）->
 * 调用方重启 MaiBot（整个目录移进 plugins 时 MaiBot 不一定能热加载）->
 * migrateConfigWhenReady 等 MaiBot 按适配器默认值生成 config.toml -> 在上面启用插件、写连接项、搬旧设置。
 * MaiBot 发现配置变了会自己补装依赖（silk-python）并热启用适配器。
 */
internal class NapCatAdapterInstaller(
    private val files: NapCatFiles,
    private val commandRunner: ContainerCommandRunner
) {
    private companion object {
        // 等 MaiBot 生成配置：重启、补装依赖、加载插件在手机上可能要几分钟。
        const val CONFIG_WAIT_TIMEOUT_MS = 5 * 60_000L
        const val CONFIG_POLL_INTERVAL_MS = 2_000L

        // 看到生成的配置后再等一会儿：MaiBot 写完配置后才订阅它的变化，太早改会错过热重载。
        const val CONFIG_SETTLE_DELAY_MS = 5_000L
    }

    private val migrationLock = Mutex()

    // 换装时从旧适配器读出的配置，迁移成功后清掉；应用中途被杀就丢了，只是少搬几项旧设置。
    @Volatile
    private var pendingLegacyConfig: String? = null

    /** 新适配器还没装，或者旧适配器还在（会和新的同时连 NapCat）时需要跑一遍 install。 */
    fun needsInstall(): Boolean = files.adapterDir() == null || files.legacyAdapterDir() != null

    /** 新适配器装好了，但配置还没迁移（MaiBot 还没生成，或连接项还没指向本机 NapCat）。 */
    fun needsConfigMigration(): Boolean = files.adapterDir() != null && !files.isAdapterConfigured()

    /** 当前 MaiBot 版本装不了新适配器时返回给用户看的原因，可以装或读不到版本号时返回 null。 */
    fun maiBotVersionProblem(): String? {
        val version = runCatching { files.maiBotPyproject.readText() }.getOrNull()?.let(::maiBotProjectVersion)
        return adapterHostVersionProblem(version)
    }

    /**
     * 先把旧适配器的配置读进内存（脚本会删掉旧目录）-> 跑安装脚本（没装才克隆，再删旧适配器）。
     * 脚本输出逐行交给 onLine；成功返回 null，失败返回原因。配置等 MaiBot 生成后由 migrateConfigWhenReady 迁移。
     */
    suspend fun install(onLine: (String) -> Unit): String? {
        val cloneAdapter = withContext(Dispatchers.IO) {
            files.legacyAdapterDir()
                ?.let { File(it, "config.toml") }
                ?.takeIf { it.isFile }
                ?.let { legacy -> runCatching { legacy.readText() }.getOrNull() }
                ?.let { pendingLegacyConfig = it }
            files.adapterDir() == null
        }
        val result = commandRunner.run(NapCatAdapterScripts.install(cloneAdapter), onLine = onLine)
        if (!result.success) return "下载 QQ 适配器失败：${result.summary()}"
        if (withContext(Dispatchers.IO) { files.adapterDir() } == null) return "QQ 适配器目录不完整"
        return null
    }

    /**
     * 等 MaiBot 生成适配器的 config.toml（带 plugin.config_version）后迁移：每 2 秒看一次，最多等 5 分钟；
     * 看到后再等 5 秒让 MaiBot 订阅好配置变化，然后启用插件、写连接项、搬旧设置。
     * 已经迁移过就直接返回。成功返回 null，失败返回原因；超时不要紧，下次启动 NapCat 会再迁移。
     */
    suspend fun migrateConfigWhenReady(onWaiting: () -> Unit = {}): String? = migrationLock.withLock {
        withContext(Dispatchers.IO) { waitAndMigrate(onWaiting) }
    }

    private suspend fun waitAndMigrate(onWaiting: () -> Unit): String? {
        val token = files.readOneBotToken() ?: return "找不到 OneBot token，请重新安装 NapCat"
        var waited = 0L
        while (true) {
            if (files.adapterDir() == null) return "找不到 QQ 适配器目录"
            val config = files.adapterConfig()
            val text = config.takeIf { it.isFile }?.readText()
            if (text != null && NapCatAdapterConfig.isMigrated(text, token)) {
                pendingLegacyConfig = null
                return null
            }
            if (text != null && NapCatAdapterConfig.isGenerated(text)) {
                delay(CONFIG_SETTLE_DELAY_MS)
                return runCatching {
                    rewriteAdapterConfig(config) { current ->
                        NapCatAdapterConfig.migrate(current ?: text, token, pendingLegacyConfig)
                    }
                    pendingLegacyConfig = null
                }.exceptionOrNull()?.let {
                    "写入 QQ 适配器配置失败：${it.message.orEmpty().ifBlank { it.javaClass.simpleName }}"
                }
            }
            if (waited >= CONFIG_WAIT_TIMEOUT_MS) {
                return "等了 5 分钟麦麦还没生成 QQ 适配器的配置，请确认麦麦在运行后重试"
            }
            if (waited == 0L) onWaiting()
            delay(CONFIG_POLL_INTERVAL_MS)
            waited += CONFIG_POLL_INTERVAL_MS
        }
    }
}

/**
 * MaiBot 版本低于适配器要求时返回提示：适配器清单写着 host_application.min_version，
 * MaiBot 会直接拒绝加载范围外的插件，装上也收不到 QQ 消息，不如先让用户更新 MaiBot。
 * 版本号读不到或解析不了时不拦（让 MaiBot 自己判断）。
 */
internal fun adapterHostVersionProblem(maiBotVersion: String?): String? {
    val current = maiBotVersion?.let(VersionNumber::parse) ?: return null
    val required = VersionNumber.parse(NapCatAdapterScripts.MIN_MAIBOT_VERSION) ?: return null
    if (current >= required) return null
    return "QQ 适配器需要 MaiBot ${NapCatAdapterScripts.MIN_MAIBOT_VERSION} 以上（当前 $maiBotVersion），" +
        "请先在设置页“MaiBot 更新”里更新 MaiBot"
}
