package org.maiwithu.maidroid.maibot

/**
 * 从 MaiBot 的 pyproject.toml 读主程序版本（[project] 的 version），读不到返回 null。
 * MaiBot 按这个版本检查插件清单的 host_application 范围，范围外的插件直接拒绝加载；
 * 装 SnowLuma 适配器前用它判断 MaiBot 够不够新（比较见 VersionNumber）。
 * pyproject 里的 version 和插件配置一样是一行内的字符串，所以借 PluginConfigToml 逐行读。
 */
internal fun maiBotProjectVersion(pyproject: String): String? =
    PluginConfigToml.rawValue(PluginConfigToml.lines(pyproject), "project", "version")
        ?.let(PluginConfigToml::parseString)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
