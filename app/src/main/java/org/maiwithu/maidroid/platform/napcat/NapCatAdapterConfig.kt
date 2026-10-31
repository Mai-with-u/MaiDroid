package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.maibot.PluginConfigToml
import java.io.File

/**
 * MaiBot 的 NapCat 适配器插件（MaiBot-Napcat-Adapter）的 config.toml，安装 NapCat 后和每次启动 NapCat 前由宿主侧改写。
 *
 * MaiDroid 只管两类配置，其余节和注释原样保留（用户可能在 MaiBot WebUI 的插件配置里改过）：
 * - 连 NapCat 的几项：[plugin].enabled、[napcat_server] 的地址、端口和 OneBot token；
 * - 聊天名单模式：[chat].group_list_type、private_list_type 没写时补成 "blacklist"（名单为空 = 全部接收）。
 *
 * 原因：适配器的名单默认是 whitelist，名单又是空的，于是扫码后所有群聊、私聊消息都被静默丢弃
 * （丢弃日志默认不打印），麦麦在 QQ 上既收不到也不会回消息（issue #3）。
 * 已经写了名单模式的不动，那是用户自己在 WebUI 里选的。
 */
internal object NapCatAdapterConfig {
    /** 与 NapCatScripts 写进 onebot11.json 的 WebSocket 服务端一致。 */
    const val ONE_BOT_HOST = "127.0.0.1"
    const val ONE_BOT_PORT = 8095

    // 与适配器 constants.py 的 SUPPORTED_CONFIG_VERSION 一致，只在第一次生成配置文件时写入；
    // 版本对不上时 MaiBot 会按默认值整份回写配置。
    private const val CONFIG_VERSION = "0.1.0"
    private const val OPEN_LIST_TYPE = "blacklist"

    private const val PLUGIN_SECTION = "plugin"
    private const val SERVER_SECTION = "napcat_server"
    private const val CHAT_SECTION = "chat"
    private val listTypeKeys = listOf("group_list_type", "private_list_type")

    /**
     * 安装 NapCat 后写入：还没有配置文件时生成完整的连接配置和放开的名单；
     * 已有配置时只启用插件、改连接三项，再补上缺的名单模式。
     */
    fun write(existing: String?, token: String): String {
        if (existing.isNullOrBlank()) {
            return PluginConfigToml.join(
                listOf(
                    "[$PLUGIN_SECTION]",
                    "enabled = true",
                    "config_version = ${PluginConfigToml.tomlString(CONFIG_VERSION)}",
                    "",
                    "[$SERVER_SECTION]",
                    "host = ${PluginConfigToml.tomlString(ONE_BOT_HOST)}",
                    "port = $ONE_BOT_PORT",
                    "token = ${PluginConfigToml.tomlString(token)}",
                    "heartbeat_interval = 30.0",
                    "reconnect_delay_sec = 5.0",
                    "action_timeout_sec = 15.0",
                    "",
                    "[$CHAT_SECTION]"
                ) + listTypeKeys.map { "$it = ${PluginConfigToml.tomlString(OPEN_LIST_TYPE)}" }
            )
        }

        val lines = PluginConfigToml.lines(existing)
        PluginConfigToml.setValue(lines, PLUGIN_SECTION, "enabled", "true")
        PluginConfigToml.setValue(lines, SERVER_SECTION, "host", PluginConfigToml.tomlString(ONE_BOT_HOST))
        PluginConfigToml.setValue(lines, SERVER_SECTION, "port", ONE_BOT_PORT.toString())
        PluginConfigToml.setValue(lines, SERVER_SECTION, "token", PluginConfigToml.tomlString(token))
        addMissingListTypes(lines)
        return PluginConfigToml.join(lines)
    }

    /**
     * 每次启动 NapCat 前调用，给旧版本 MaiDroid 装好的配置补上名单模式；什么都不缺时原样返回，调用方据此跳过写文件。
     */
    fun withOpenChatLists(existing: String): String {
        val lines = PluginConfigToml.lines(existing)
        if (!addMissingListTypes(lines)) return existing
        return PluginConfigToml.join(lines)
    }

    // 只补没写的名单模式键，返回是否改了内容。
    private fun addMissingListTypes(lines: MutableList<String>): Boolean {
        var changed = false
        listTypeKeys.forEach { key ->
            if (PluginConfigToml.rawValue(lines, CHAT_SECTION, key) == null) {
                PluginConfigToml.setValue(lines, CHAT_SECTION, key, PluginConfigToml.tomlString(OPEN_LIST_TYPE))
                changed = true
            }
        }
        return changed
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
