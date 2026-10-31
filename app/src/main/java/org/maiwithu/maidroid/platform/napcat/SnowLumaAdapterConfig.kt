package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.maibot.PluginConfigToml
import java.io.File

/**
 * MaiBot 侧 SnowLuma 适配器插件（MaiBot-SnowLuma-Adapter，见 SnowLumaAdapterScripts）的 config.toml 迁移。
 *
 * MaiDroid 不自己从头写这份配置：MaiBot 第一次加载插件时会按适配器的默认值生成一份完整的配置
 * （含 MaiBot 强制要求的 plugin.config_version，以及适配器当前版本的所有配置段），
 * MaiDroid 等它生成后再在上面改（SnowLumaAdapterInstaller.migrateConfigWhenReady）：
 * 启用插件（适配器默认关着，不开不会去连）-> [client] 指向本机 NapCat（地址、端口、OneBot token）->
 * 旧适配器 MaiBot-Napcat-Adapter 的配置里有对应项的设置原样搬过来。其余节和注释原样保留。
 * MaiBot 监听插件配置文件，改完会自己补装依赖并热启用适配器。
 */
internal object SnowLumaAdapterConfig {
    /** 与 NapCatScripts 写进 onebot11.json 的 WebSocket 服务端一致。 */
    const val ONE_BOT_HOST = "127.0.0.1"
    const val ONE_BOT_PORT = 8095

    private const val PLUGIN_SECTION = "plugin"
    private const val CLIENT_SECTION = "client"

    /** 旧适配器配置项 -> 新适配器配置项：（旧节, 旧键, 新节, 新键）。旧的群聊、私聊名单新适配器没有对应功能，不搬。 */
    private val legacySettings = listOf(
        LegacySetting("plugin", "enable_private_chat_tool", "chat_abilities", "enable_private_chat_tool"),
        LegacySetting("chat", "ban_user_id", "filters", "ban_user_id"),
        LegacySetting("chat", "ban_qq_bot", "filters", "ban_qq_bot"),
        LegacySetting("filters", "ignore_self_message", "debug", "ignore_self_message"),
        LegacySetting("filters", "regex_filter_enabled", "filters", "regex_filter_enabled"),
        LegacySetting("filters", "regex_filter_mode", "filters", "regex_filter_mode"),
        LegacySetting("filters", "regex_filter_patterns", "filters", "regex_filter_patterns"),
        LegacySetting("filters", "regex_filter_show_dropped", "filters", "regex_filter_show_dropped"),
        LegacySetting("notice", "enabled", "notice", "enabled")
    )

    private data class LegacySetting(
        val legacySection: String,
        val legacyKey: String,
        val section: String,
        val key: String
    )

    /** MaiBot 按适配器默认值生成的配置都带 plugin.config_version；没有就是还没生成（或不是 MaiBot 写的）。 */
    fun isGenerated(text: String): Boolean =
        PluginConfigToml.rawValue(PluginConfigToml.lines(text), PLUGIN_SECTION, "config_version")
            ?.let(PluginConfigToml::parseString)
            .isNullOrBlank()
            .not()

    /**
     * [client] 的地址、端口、token 都指向本机 NapCat 就算迁移过。
     * 只看连接项、不看 enabled：用户之后在 WebUI 里关掉适配器是他自己的选择，不能每次启动都给打开。
     */
    fun isMigrated(text: String, token: String): Boolean {
        val lines = PluginConfigToml.lines(text)
        val server = PluginConfigToml.rawValue(lines, CLIENT_SECTION, "server")?.let(PluginConfigToml::parseString)
        val port = PluginConfigToml.rawValue(lines, CLIENT_SECTION, "port")?.substringBefore('#')?.trim()
        val savedToken = PluginConfigToml.rawValue(lines, CLIENT_SECTION, "token")?.let(PluginConfigToml::parseString)
        return server == ONE_BOT_HOST && port == ONE_BOT_PORT.toString() && savedToken == token
    }

    /**
     * 在 MaiBot 生成的配置上迁移：启用插件 -> 写连接三项 -> 搬旧适配器的对应设置（legacy 为空就跳过）。
     * 旧值只搬写在一行内的，多行数组搬过来会写坏文件；新配置里本来没有这一项的也照样补上，交给适配器自己校验。
     */
    fun migrate(generated: String, token: String, legacy: String?): String {
        val lines = PluginConfigToml.lines(generated)
        PluginConfigToml.setValue(lines, PLUGIN_SECTION, "enabled", "true")
        PluginConfigToml.setValue(lines, CLIENT_SECTION, "server", PluginConfigToml.tomlString(ONE_BOT_HOST))
        PluginConfigToml.setValue(lines, CLIENT_SECTION, "port", ONE_BOT_PORT.toString())
        PluginConfigToml.setValue(lines, CLIENT_SECTION, "token", PluginConfigToml.tomlString(token))
        if (!legacy.isNullOrBlank()) {
            val legacyLines = PluginConfigToml.lines(legacy)
            legacySettings.forEach { setting ->
                val raw = PluginConfigToml.rawValue(legacyLines, setting.legacySection, setting.legacyKey)
                    ?.trim()
                    ?.takeIf(PluginConfigToml::isCompleteValue)
                    ?: return@forEach
                PluginConfigToml.setValue(lines, setting.section, setting.key, raw)
            }
        }
        return PluginConfigToml.join(lines)
    }
}

/**
 * 改写适配器配置文件：内容没变就不写；变了先写同目录临时文件再改名替换，
 * 避免 MaiBot 的配置热重载读到写了一半的文件。返回是否真的写了。
 */
internal fun rewriteAdapterConfig(file: File, transform: (String?) -> String): Boolean {
    val existing = file.takeIf { it.isFile }?.readText()
    val updated = transform(existing)
    if (updated == existing) return false

    file.parentFile?.mkdirs()
    // MaiBot 只在 .py 和 _manifest.json 变化时重载插件，临时文件不会触发重载。
    val staging = File(file.parentFile, "${file.name}.maidroid.tmp")
    staging.writeText(updated)
    if (!staging.renameTo(file)) {
        staging.delete()
        error("无法写入 ${file.name}")
    }
    return true
}
