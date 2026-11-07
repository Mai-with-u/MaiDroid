package org.maiwithu.maidroid.platform.qq

import org.maiwithu.maidroid.maibot.findPluginDirById
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/**
 * NapCat 和 SnowLuma 两种 QQ 协议端共用的文件：MaiBot 侧的 SnowLuma 适配器插件和 OneBot 访问 token。
 * 两边开的 OneBot 服务端地址、端口和 token 都一样（QqOneBot），换协议端时适配器配置不用动。
 */
internal class QqAdapterFiles(paths: RuntimePaths) {
    private val maiBotPluginsDir: File = paths.maiBotPluginsDir

    /** 第一次安装 QQ 协议端时生成的 OneBot 访问 token，重装、换协议端都沿用；宿主侧写适配器配置时从这里读。 */
    val oneBotTokenFile: File = File(paths.rootfsDir, QqOneBot.TOKEN_PATH.removePrefix("/"))

    /** MaiBot 的 pyproject.toml，读主程序版本判断能不能装 SnowLuma 适配器。 */
    val maiBotPyproject: File = paths.maiBotPyproject

    /**
     * MaiBot 侧的 SnowLuma 适配器插件（SnowLumaAdapterScripts）：和 MaiBot 一样按清单 ID 找，
     * 用户也可能从 MaiBot WebUI 的插件市场装过，只认固定目录名会再装一份、同一插件 ID 出现两份会冲突。
     */
    fun adapterDir(): File? =
        findPluginDirById(maiBotPluginsDir, SnowLumaAdapterScripts.PLUGIN_ID, SnowLumaAdapterScripts.DIR_NAME)

    /** 已停更的旧适配器（MaiBot-Napcat-Adapter）还在就返回它的目录，迁移时要删掉。 */
    fun legacyAdapterDir(): File? =
        findPluginDirById(maiBotPluginsDir, SnowLumaAdapterScripts.LEGACY_PLUGIN_ID, SnowLumaAdapterScripts.LEGACY_DIR_NAME)

    /** SnowLuma 适配器的配置，里面写着连 OneBot 用的端口和 token，由 SnowLumaAdapterConfig 改写；还没装时指向将要安装的位置。 */
    fun adapterConfig(): File =
        File(adapterDir() ?: File(maiBotPluginsDir, SnowLumaAdapterScripts.DIR_NAME), "config.toml")

    /** 第一次安装时保存的 OneBot token，读不到返回 null。 */
    fun readOneBotToken(): String? = oneBotTokenFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** SnowLuma 适配器装好了，并且它的配置已经迁移成连本机的 QQ 协议端（见 SnowLumaAdapterConfig.isMigrated）。 */
    fun isAdapterConfigured(): Boolean {
        if (adapterDir() == null) return false
        val config = adapterConfig().takeIf { it.isFile }?.readText() ?: return false
        val token = readOneBotToken() ?: return false
        return SnowLumaAdapterConfig.isMigrated(config, token)
    }
}
