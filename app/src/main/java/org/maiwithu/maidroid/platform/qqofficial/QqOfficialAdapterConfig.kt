package org.maiwithu.maidroid.platform.qqofficial

import org.maiwithu.maidroid.maibot.PluginConfigToml

/** QQ 官方机器人适配器 config.toml 里 MaiDroid 关心的三项：插件是否启用、AppID、AppSecret。 */
internal data class QqOfficialCredentials(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = ""
) {
    // 三项齐全适配器才会去连 QQ 开放平台；缺凭据时适配器只打一行错误就不再重试。
    val configured: Boolean = enabled && appId.isNotBlank() && appSecret.isNotBlank()
}

/**
 * 读写适配器的 config.toml。
 *
 * 只动 [plugin].enabled、[qq_official].app_id、[qq_official].app_secret 三行，其余节和注释原样保留：
 * 禁言、撤回、统一账号 ID 这些用户可能在 MaiBot WebUI 的插件配置里改过，不能被覆盖。逐行读写交给 PluginConfigToml。
 */
internal object QqOfficialAdapterConfig {
    // 外置插件旧版（settings.py 0.4.1）与 MaiBot 1.4.x 内置插件（0.5.4）各自的默认配置版本；
    // 仅在 config.toml 尚未生成时按需写入，已有配置一律保留原版本号以免触发误迁移。
    internal const val LegacyConfigVersion = "0.4.1"
    internal const val BuiltInConfigVersion = "0.5.4"
    private const val PluginSection = "plugin"
    private const val CredentialSection = "qq_official"

    fun read(text: String?): QqOfficialCredentials {
        if (text.isNullOrBlank()) return QqOfficialCredentials()
        val lines = text.lines()
        return QqOfficialCredentials(
            enabled = PluginConfigToml.rawValue(lines, PluginSection, "enabled")
                ?.let(PluginConfigToml::parseBoolean) ?: false,
            appId = PluginConfigToml.rawValue(lines, CredentialSection, "app_id")
                ?.let(PluginConfigToml::parseString).orEmpty(),
            appSecret = PluginConfigToml.rawValue(lines, CredentialSection, "app_secret")
                ?.let(PluginConfigToml::parseString).orEmpty()
        )
    }

    /**
     * 写入凭据并启用插件：还没有配置文件时生成最小配置（其余字段由 MaiBot 按默认值补齐）；
     * 已有配置时逐行替换这三项，节或键不存在就补上，原 config_version 与其他节完整保留。
     */
    fun write(
        existing: String?,
        appId: String,
        appSecret: String,
        defaultConfigVersion: String = LegacyConfigVersion
    ): String {
        if (existing.isNullOrBlank()) {
            return PluginConfigToml.join(
                listOf(
                    "[$PluginSection]",
                    "enabled = true",
                    "config_version = ${PluginConfigToml.tomlString(defaultConfigVersion)}",
                    "",
                    "[$CredentialSection]",
                    "app_id = ${PluginConfigToml.tomlString(appId)}",
                    "app_secret = ${PluginConfigToml.tomlString(appSecret)}"
                )
            )
        }

        val lines = PluginConfigToml.lines(existing)
        PluginConfigToml.setValue(lines, PluginSection, "enabled", "true")
        PluginConfigToml.setValue(lines, CredentialSection, "app_id", PluginConfigToml.tomlString(appId))
        PluginConfigToml.setValue(lines, CredentialSection, "app_secret", PluginConfigToml.tomlString(appSecret))
        return PluginConfigToml.join(lines)
    }
}
