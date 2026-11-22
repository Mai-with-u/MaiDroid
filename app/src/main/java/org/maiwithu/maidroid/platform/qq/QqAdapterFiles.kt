package org.maiwithu.maidroid.platform.qq

import org.maiwithu.maidroid.maibot.MaiBotInstance
import org.maiwithu.maidroid.maibot.MaiBotInstances
import org.maiwithu.maidroid.maibot.findPluginDirById
import org.maiwithu.maidroid.maibot.findPluginDirsById
import org.maiwithu.maidroid.maibot.maiBotProjectVersion
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/**
 * NapCat 和 SnowLuma 两种 QQ 协议端共用的文件：MaiBot 侧的 SnowLuma 适配器插件和 OneBot 访问 token。
 * 两边开的 OneBot 服务端地址、端口和 token 都一样（QqOneBot），换协议端时适配器配置不用动。
 *
 * 注意：1.4.x 全新安装会把实例建在 `/opt/MaiBot/bots/<id>/` 下，所以每次读写都通过 [MaiBotInstances]
 * 动态解析当前活跃实例的 plugins/temp 目录，不在构造阶段把路径写死到根目录。
 */
internal class QqAdapterFiles(private val paths: RuntimePaths) {
    /** 第一次安装 QQ 协议端时生成的 OneBot 访问 token，重装、换协议端都沿用；宿主侧写适配器配置时从这里读。 */
    val oneBotTokenFile: File = File(paths.rootfsDir, QqOneBot.TOKEN_PATH.removePrefix("/"))

    /** MaiBot 的 pyproject.toml，读主程序版本判断能不能装 SnowLuma 适配器。 */
    val maiBotPyproject: File = paths.maiBotPyproject

    /** 解析当前应该安装、读取配置和日志的 MaiBot 实例；首次引导没跑过时回退到根目录。 */
    fun targetInstance(): MaiBotInstance =
        MaiBotInstances.resolvePluginTarget(paths, SnowLumaAdapterScripts.PLUGIN_ID, SnowLumaAdapterScripts.DIR_NAME)

    /** 新适配器克隆暂存目录（以点开头，校验版本兼容后再移动为正式目录）。 */
    fun stagingAdapterDir(instance: MaiBotInstance = targetInstance()): File =
        File(instance.pluginsDir, SnowLumaAdapterScripts.STAGING_DIR_NAME)

    /** MaiDroid 自己安装新适配器时的正式目录。 */
    fun targetAdapterDir(instance: MaiBotInstance = targetInstance()): File =
        File(instance.pluginsDir, SnowLumaAdapterScripts.DIR_NAME)

    /**
     * MaiBot 侧的 SnowLuma 适配器插件（SnowLumaAdapterScripts）：和 MaiBot 一样按清单 ID 找，
     * 用户也可能从 MaiBot WebUI 的插件市场装过，只认固定目录名会再装一份、同一插件 ID 出现两份会冲突。
     */
    fun adapterDir(instance: MaiBotInstance = targetInstance()): File? =
        findPluginDirById(instance.pluginsDir, SnowLumaAdapterScripts.PLUGIN_ID, SnowLumaAdapterScripts.DIR_NAME)

    /** 已停更的旧适配器（MaiBot-Napcat-Adapter）的所有目录（MaiDroid 装的或插件市场装的），新适配器兼容后要一并删掉。 */
    fun legacyAdapterDirs(instance: MaiBotInstance = targetInstance()): List<File> =
        findPluginDirsById(instance.pluginsDir, SnowLumaAdapterScripts.LEGACY_PLUGIN_ID, SnowLumaAdapterScripts.LEGACY_DIR_NAME)

    /** 已停更的旧适配器（MaiBot-Napcat-Adapter）还在就返回它的首选目录，迁移时要删掉。 */
    fun legacyAdapterDir(instance: MaiBotInstance = targetInstance()): File? =
        legacyAdapterDirs(instance).firstOrNull()

    /** 从 MaiBot 的 pyproject.toml 读取主程序版本，读不到返回 null。 */
    fun readMaiBotVersion(): String? =
        runCatching { maiBotPyproject.takeIf { it.isFile }?.readText() }.getOrNull()?.let(::maiBotProjectVersion)

    /** SnowLuma 适配器的配置，里面写着连 OneBot 用的端口和 token，由 SnowLumaAdapterConfig 改写；还没装时指向将要安装的位置。 */
    fun adapterConfig(instance: MaiBotInstance = targetInstance()): File =
        File(adapterDir(instance) ?: targetAdapterDir(instance), "config.toml")

    /** 1.4.x Worker 的控制台日志文件，等配置超时时附带从这里排查插件加载/依赖报错。 */
    fun workerConsoleLog(instance: MaiBotInstance = targetInstance()): File = instance.workerConsoleLog

    /**
     * 检查根目录 `/opt/MaiBot/plugins` 是否残存以前错装的未配置 QQ 适配器副本：
     * 仅当目标实例不在根目录、根目录本身也不在已登记实例列表中，且该副本匹配插件 ID 又从未生成 `config.toml` 时才返回，
     * 避免误删用户自己放的插件或根目录旧实例配置。
     */
    fun orphanRootAdapterDir(target: MaiBotInstance): File? {
        if (target.dir == paths.maiBotDir) return null
        if (MaiBotInstances.discover(paths).any { it.dir == paths.maiBotDir }) return null
        return findPluginDirsById(paths.maiBotPluginsDir, SnowLumaAdapterScripts.PLUGIN_ID, SnowLumaAdapterScripts.DIR_NAME)
            .firstOrNull { dir ->
                dir.name == SnowLumaAdapterScripts.DIR_NAME && !File(dir, "config.toml").exists()
            }
    }

    /** 第一次安装时保存的 OneBot token，读不到返回 null。 */
    fun readOneBotToken(): String? = oneBotTokenFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** SnowLuma 适配器装好了，并且它的配置已经迁移成连本机的 QQ 协议端（见 SnowLumaAdapterConfig.isMigrated）。 */
    fun isAdapterConfigured(instance: MaiBotInstance = targetInstance()): Boolean {
        if (adapterDir(instance) == null) return false
        val config = adapterConfig(instance).takeIf { it.isFile }?.readText() ?: return false
        val token = readOneBotToken() ?: return false
        return SnowLumaAdapterConfig.isMigrated(config, token)
    }
}
