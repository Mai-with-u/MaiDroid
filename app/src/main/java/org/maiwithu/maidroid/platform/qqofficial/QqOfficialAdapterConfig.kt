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
    // 外置插件与 MaiBot 内置插件在读不到 settings.py 默认值时的兜底配置版本；
    // 优先从容器内插件的 settings.py 动态提取默认 config_version，已有配置一律保留原版本号以免触发误迁移。
    internal const val FallbackLegacyConfigVersion = "0.4.1"
    internal const val FallbackBuiltInConfigVersion = "0.5.4"
    private const val PluginSection = "plugin"
    private const val CredentialSection = "qq_official"

    // 匹配插件 settings.py 中的 config_version 默认值，兼容直接赋值与 Pydantic Field(default=...)；
    // 行首按空白锚定，自动跳过 `#` 注释行。
    private val settingsDefaultConfigVersionRegex = Regex(
        """(?m)^\s*config_version\s*(?::[^=]+)?=\s*(?:Field\s*\([^)]*?default\s*=\s*)?["']([^"'\r\n]+)["']"""
    )

    /** 从插件 `settings.py` 提取 `config_version` 默认值；找不到或文本为空时返回 null。 */
    fun parseDefaultConfigVersion(settingsPyText: String?): String? {
        if (settingsPyText.isNullOrBlank()) return null
        return settingsDefaultConfigVersionRegex.find(settingsPyText)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

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
        defaultConfigVersion: String = FallbackLegacyConfigVersion
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
