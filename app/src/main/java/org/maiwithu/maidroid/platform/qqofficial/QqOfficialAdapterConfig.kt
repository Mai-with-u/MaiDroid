package org.maiwithu.maidroid.platform.qqofficial

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
 * 禁言、撤回、统一账号 ID 这些用户可能在 MaiBot WebUI 的插件配置里改过，不能被覆盖。
 * 只认 MaiBot 写回配置时用的单行写法（`key = "value"`、`key = true`），不是通用 TOML 解析器。
 */
internal object QqOfficialAdapterConfig {
    // 与适配器 settings.py 的默认配置版本一致，只在第一次生成配置文件时写入。
    private const val ConfigVersion = "0.4.1"
    private const val PluginSection = "plugin"
    private const val CredentialSection = "qq_official"

    private val TableHeader = Regex("""^\s*\[\s*([A-Za-z0-9_.-]+)\s*]\s*(#.*)?$""")
    private val KeyValue = Regex("""^\s*([A-Za-z0-9_-]+)\s*=\s*(.*)$""")

    fun read(text: String?): QqOfficialCredentials {
        if (text.isNullOrBlank()) return QqOfficialCredentials()
        val lines = text.lines()
        return QqOfficialCredentials(
            enabled = rawValue(lines, PluginSection, "enabled")?.let(::parseBoolean) ?: false,
            appId = rawValue(lines, CredentialSection, "app_id")?.let(::parseString).orEmpty(),
            appSecret = rawValue(lines, CredentialSection, "app_secret")?.let(::parseString).orEmpty()
        )
    }

    /**
     * 写入凭据并启用插件：还没有配置文件时生成最小配置（其余字段由 MaiBot 按默认值补齐）；
     * 已有配置时逐行替换这三项，节或键不存在就补上。
     */
    fun write(existing: String?, appId: String, appSecret: String): String {
        if (existing.isNullOrBlank()) {
            return listOf(
                "[$PluginSection]",
                "enabled = true",
                "config_version = ${tomlString(ConfigVersion)}",
                "",
                "[$CredentialSection]",
                "app_id = ${tomlString(appId)}",
                "app_secret = ${tomlString(appSecret)}"
            ).joinToString("\n", postfix = "\n")
        }

        val lines = existing.trimEnd('\n', '\r').lines().toMutableList()
        setValue(lines, PluginSection, "enabled", "true")
        setValue(lines, CredentialSection, "app_id", tomlString(appId))
        setValue(lines, CredentialSection, "app_secret", tomlString(appSecret))
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun sectionName(line: String): String? = TableHeader.matchEntire(line)?.groupValues?.get(1)

    // 数组表 [[x]] 也算一节的结束，只是不会被当成目标节。
    private fun isTableHeader(line: String): Boolean = line.trimStart().startsWith("[")

    private fun sectionRange(lines: List<String>, section: String): IntRange? {
        val start = lines.indexOfFirst { sectionName(it) == section }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { isTableHeader(lines[it]) } ?: lines.size
        return (start + 1) until end
    }

    private fun rawValue(lines: List<String>, section: String, key: String): String? {
        val range = sectionRange(lines, section) ?: return null
        return range.firstNotNullOfOrNull { index ->
            KeyValue.matchEntire(lines[index])
                ?.takeIf { it.groupValues[1] == key }
                ?.groupValues
                ?.get(2)
        }
    }

    private fun setValue(lines: MutableList<String>, section: String, key: String, value: String) {
        val entry = "$key = $value"
        val range = sectionRange(lines, section)
        if (range == null) {
            // 整节都没有：在文件末尾空一行补上新节。
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("[$section]")
            lines.add(entry)
            return
        }
        val keyIndex = range.firstOrNull { KeyValue.matchEntire(lines[it])?.groupValues?.get(1) == key }
        if (keyIndex != null) {
            lines[keyIndex] = entry
            return
        }
        // 节在但缺这个键：插在本节最后一个非空行之后，不把它挤到下一节前面的空行里。
        var insertAt = range.last + 1
        while (insertAt > range.first && lines[insertAt - 1].isBlank()) insertAt--
        lines.add(insertAt, entry)
    }

    private fun parseBoolean(raw: String): Boolean? =
        when (raw.substringBefore('#').trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    // 支持基本字符串（双引号，带转义）和字面量字符串（单引号，不转义）；其他写法当作没填。
    private fun parseString(raw: String): String? {
        val value = raw.trim()
        if (value.startsWith("'")) {
            val end = value.indexOf('\'', startIndex = 1)
            return if (end > 0) value.substring(1, end) else null
        }
        if (!value.startsWith("\"")) return null
        val result = StringBuilder()
        var index = 1
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> return result.toString()
                char == '\\' && index + 1 < value.length -> {
                    val escaped = value[index + 1]
                    when (escaped) {
                        'n' -> result.append('\n')
                        't' -> result.append('\t')
                        'r' -> result.append('\r')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        'u', 'U' -> {
                            val length = if (escaped == 'u') 4 else 8
                            val hex = value.substring(index + 2, (index + 2 + length).coerceAtMost(value.length))
                            val codePoint = hex.takeIf { it.length == length }?.toIntOrNull(16) ?: return null
                            result.appendCodePoint(codePoint)
                            index += length
                        }
                        else -> return null
                    }
                    index += 2
                }
                else -> {
                    result.append(char)
                    index++
                }
            }
        }
        return null
    }

    internal fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when {
                char == '\\' -> append("\\\\")
                char == '"' -> append("\\\"")
                char == '\n' -> append("\\n")
                char == '\t' -> append("\\t")
                char == '\r' -> append("\\r")
                char.code < 0x20 || char.code == 0x7F -> append("\\u%04X".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
