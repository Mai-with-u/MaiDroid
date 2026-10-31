package org.maiwithu.maidroid.maibot

/**
 * 逐行读写 MaiBot 插件 config.toml 的工具，各平台适配器的配置读写（QqOfficialAdapterConfig、NapCatAdapterConfig）共用。
 *
 * 只改目标节里的目标键，其余节、键和注释原样保留：用户可能在 MaiBot WebUI 的插件配置里改过别的项，不能被覆盖。
 * 只认 MaiBot 写回配置时用的单行写法（`key = "value"`、`key = true`），不是通用 TOML 解析器。
 */
internal object PluginConfigToml {
    private val TableHeader = Regex("""^\s*\[\s*([A-Za-z0-9_.-]+)\s*]\s*(#.*)?$""")
    private val KeyValue = Regex("""^\s*([A-Za-z0-9_-]+)\s*=\s*(.*)$""")

    /** 拆成可就地修改的行；末尾的空行去掉，join 时统一补一个换行。 */
    fun lines(text: String): MutableList<String> = text.trimEnd('\n', '\r').lines().toMutableList()

    fun join(lines: List<String>): String = lines.joinToString("\n", postfix = "\n")

    /** 读 [section] 里 key 等号右边的原始文本（含引号和行尾注释），节或键不存在时返回 null。 */
    fun rawValue(lines: List<String>, section: String, key: String): String? {
        val range = sectionRange(lines, section) ?: return null
        return range.firstNotNullOfOrNull { index ->
            KeyValue.matchEntire(lines[index])
                ?.takeIf { it.groupValues[1] == key }
                ?.groupValues
                ?.get(2)
        }
    }

    /**
     * 把 [section] 里的 key 设成 value（value 是已经写成 TOML 的文本，字符串要先过 tomlString）：
     * 键在就整行替换；节在但缺键就插在本节最后一个非空行之后；整节都没有就在文件末尾空一行补上新节。
     */
    fun setValue(lines: MutableList<String>, section: String, key: String, value: String) {
        val entry = "$key = $value"
        val range = sectionRange(lines, section)
        if (range == null) {
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
        // 不把新键挤到下一节前面的空行里。
        var insertAt = range.last + 1
        while (insertAt > range.first && lines[insertAt - 1].isBlank()) insertAt--
        lines.add(insertAt, entry)
    }

    fun parseBoolean(raw: String): Boolean? =
        when (raw.substringBefore('#').trim()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    // 支持基本字符串（双引号，带转义）和字面量字符串（单引号，不转义）；其他写法当作没填。
    fun parseString(raw: String): String? {
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

    fun tomlString(value: String): String = buildString {
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

    private fun sectionName(line: String): String? = TableHeader.matchEntire(line)?.groupValues?.get(1)

    // 数组表 [[x]] 也算一节的结束，只是不会被当成目标节。
    private fun isTableHeader(line: String): Boolean = line.trimStart().startsWith("[")

    private fun sectionRange(lines: List<String>, section: String): IntRange? {
        val start = lines.indexOfFirst { sectionName(it) == section }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { isTableHeader(lines[it]) } ?: lines.size
        return (start + 1) until end
    }
}
