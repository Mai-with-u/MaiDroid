package org.maiwithu.maidroid.webui

import java.net.URI
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** 展平 `<select>` 菜单模式：单选、多选或上下文菜单。 */
internal enum class WebUiChoiceMode {
    Single,
    Multiple,
    Menu
}

/**
 * 网页原生 `<select>` / alert / confirm / prompt / color 弹窗的纯状态请求模型，
 * 供 [WebUiSessionHost] 发射给 Compose 液态玻璃弹窗宿主展示并结算用户选择。
 */
internal sealed interface WebUiPromptRequest {
    val id: Long
    val onDismiss: () -> Unit

    data class Choice(
        override val id: Long,
        val title: String,
        val message: String,
        val mode: WebUiChoiceMode,
        val rows: List<WebUiChoiceRow>,
        val initialSelectedIds: Set<String>,
        val onConfirmSingle: (String) -> Unit,
        val onConfirmMultiple: (Array<String>) -> Unit,
        override val onDismiss: () -> Unit
    ) : WebUiPromptRequest

    data class Alert(
        override val id: Long,
        val title: String,
        val message: String,
        override val onDismiss: () -> Unit
    ) : WebUiPromptRequest

    data class Confirm(
        override val id: Long,
        val title: String,
        val message: String,
        val onConfirm: () -> Unit,
        val onCancel: () -> Unit,
        override val onDismiss: () -> Unit
    ) : WebUiPromptRequest

    data class TextInput(
        override val id: Long,
        val title: String,
        val message: String,
        val defaultValue: String,
        val placeholder: String,
        val allowClear: Boolean,
        val onConfirm: (String) -> Unit,
        override val onDismiss: () -> Unit
    ) : WebUiPromptRequest

    data class ColorPicker(
        override val id: Long,
        val title: String,
        val initialHex: String,
        val paletteHexes: List<String>,
        val onConfirm: (String) -> Unit,
        override val onDismiss: () -> Unit
    ) : WebUiPromptRequest
}

/**
 * 网页原生表单选项树的简化节点，剥离 GeckoView 依赖以便在 JVM 单测中直接校验展平与多选顺序逻辑。
 */
internal data class WebUiChoiceNode(
    val id: String,
    val label: String,
    val disabled: Boolean = false,
    val selected: Boolean = false,
    val separator: Boolean = false,
    val items: List<WebUiChoiceNode> = emptyList()
)

/**
 * 展平后的 `<select>` 弹窗列表项：分别对应普通选项、`<optgroup>` 分组标题与 `<hr>` 分隔线。
 */
internal sealed interface WebUiChoiceRow {
    data object Separator : WebUiChoiceRow

    data class GroupHeader(
        val label: String,
        val disabled: Boolean,
        val depth: Int
    ) : WebUiChoiceRow

    data class Option(
        val id: String,
        val label: String,
        val disabled: Boolean,
        val selected: Boolean,
        val depth: Int
    ) : WebUiChoiceRow
}

/**
 * 将 GeckoView 传入的多层 `<select>`/`<optgroup>` 树展平为一维列表。
 * 父分组被禁用时，子项与嵌套分组统一继承禁用状态；分隔线单独占行，不递归展开子项。
 */
internal fun flattenChoiceItems(
    nodes: List<WebUiChoiceNode>,
    parentDisabled: Boolean = false,
    depth: Int = 0
): List<WebUiChoiceRow> {
    if (nodes.isEmpty()) return emptyList()
    val rows = ArrayList<WebUiChoiceRow>(nodes.size)
    fun appendNodes(items: List<WebUiChoiceNode>, inheritedDisabled: Boolean, level: Int) {
        for (node in items) {
            if (node.separator) {
                rows.add(WebUiChoiceRow.Separator)
                continue
            }
            val effectiveDisabled = inheritedDisabled || node.disabled
            if (node.items.isNotEmpty()) {
                rows.add(
                    WebUiChoiceRow.GroupHeader(
                        label = node.label,
                        disabled = effectiveDisabled,
                        depth = level
                    )
                )
                appendNodes(node.items, effectiveDisabled, level + 1)
            } else {
                rows.add(
                    WebUiChoiceRow.Option(
                        id = node.id,
                        label = node.label,
                        disabled = effectiveDisabled,
                        selected = node.selected,
                        depth = level
                    )
                )
            }
        }
    }
    appendNodes(nodes, parentDisabled, depth)
    return rows
}

/**
 * 按关键字过滤 `<select>` 展平列表：大小写不敏感匹配选项文案，仅保留包含命中项的祖先分组标题，
 * 并在搜索非空时隐藏纯粹的 `<hr>` 分隔线以保持检索结果紧凑清晰。
 */
internal fun filterChoiceRows(rows: List<WebUiChoiceRow>, query: String): List<WebUiChoiceRow> {
    val needle = query.trim()
    if (needle.isEmpty() || rows.isEmpty()) return rows

    val filtered = ArrayList<WebUiChoiceRow>(rows.size)
    // 记录当前活跃祖先分组及该分组是否已输出过，避免同组多条命中重复追加标题。
    val activeHeaders = ArrayList<Pair<WebUiChoiceRow.GroupHeader, Boolean>>()

    for (row in rows) {
        when (row) {
            is WebUiChoiceRow.Separator -> continue
            is WebUiChoiceRow.GroupHeader -> {
                while (activeHeaders.isNotEmpty() && activeHeaders.last().first.depth >= row.depth) {
                    activeHeaders.removeAt(activeHeaders.lastIndex)
                }
                activeHeaders.add(row to false)
            }
            is WebUiChoiceRow.Option -> {
                while (activeHeaders.isNotEmpty() && activeHeaders.last().first.depth >= row.depth) {
                    activeHeaders.removeAt(activeHeaders.lastIndex)
                }
                if (row.label.contains(needle, ignoreCase = true)) {
                    for (index in activeHeaders.indices) {
                        val (header, emitted) = activeHeaders[index]
                        if (!emitted) {
                            filtered.add(header)
                            activeHeaders[index] = header to true
                        }
                    }
                    filtered.add(row)
                }
            }
        }
    }
    return filtered
}

/**
 * 收集初始即处于选中态的全部选项 ID（包含被禁用但原本已选中的项，避免多选确认时意外丢失原选择）。
 */
internal fun initialSelectedChoiceIds(rows: List<WebUiChoiceRow>): LinkedHashSet<String> {
    val selected = LinkedHashSet<String>()
    for (row in rows) {
        if (row is WebUiChoiceRow.Option && row.selected) {
            selected.add(row.id)
        }
    }
    return selected
}

/**
 * 返回展平列表里第一个初始选中选项的行索引，供打开弹窗时自动滚动定位；无选中项返回 -1。
 */
internal fun initialSelectedOptionIndex(rows: List<WebUiChoiceRow>): Int =
    rows.indexOfFirst { it is WebUiChoiceRow.Option && it.selected }

/**
 * 按原始选项顺序导出已选 ID 数组，保证提交给 GeckoView 的结果顺序与网页 DOM 一致。
 */
internal fun selectedChoiceIdsInOrder(
    rows: List<WebUiChoiceRow>,
    selectedIds: Set<String>
): Array<String> {
    if (selectedIds.isEmpty()) return emptyArray()
    val ordered = ArrayList<String>(selectedIds.size)
    for (row in rows) {
        if (row is WebUiChoiceRow.Option && row.id in selectedIds) {
            ordered.add(row.id)
        }
    }
    return ordered.toTypedArray()
}

/** 解析后的日期三元组（month 为 1..12）。 */
internal data class WebUiDateValue(
    val year: Int,
    val month: Int,
    val day: Int
)

/** 解析后的 24 小时制时间二元组（hour 为 0..23，minute 为 0..59）。 */
internal data class WebUiTimeValue(
    val hour: Int,
    val minute: Int
)

/** 解析后的本地日期时间组合，用于 `<input type="datetime-local">` 兩步选择器。 */
internal data class WebUiDateTimeValue(
    val date: WebUiDateValue,
    val time: WebUiTimeValue
)

private val dateRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
private val timeRegex = Regex("""^(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$""")
private val dateTimeSplitRegex = Regex("""[Tt ]""")

/** 严格解析 HTML `yyyy-MM-dd` 日期串，拒绝不存在的月份或日期（如 2026-02-30）。 */
internal fun parseDateValue(raw: String?): WebUiDateValue? {
    val text = raw?.trim().orEmpty()
    val match = dateRegex.matchEntire(text) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (month !in 1..12) return null
    val maxDay = daysInMonth(year, month)
    if (day !in 1..maxDay) return null
    return WebUiDateValue(year = year, month = month, day = day)
}

/** 将年月日格式化为 HTML 标准的 `yyyy-MM-dd` 字符串。 */
internal fun formatDateValue(year: Int, month: Int, day: Int): String =
    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)

/** 解析 HTML `HH:mm` 或 `HH:mm:ss(.SSS)` 时间串，截断保留到分钟供原生时间选择器使用。 */
internal fun parseTimeValue(raw: String?): WebUiTimeValue? {
    val text = raw?.trim().orEmpty()
    val match = timeRegex.matchEntire(text) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    val second = match.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
    if (hour !in 0..23 || minute !in 0..59) return null
    if (second != null && second !in 0..59) return null
    return WebUiTimeValue(hour = hour, minute = minute)
}

/** 将时分格式化为 HTML 标准的 `HH:mm` 字符串。 */
internal fun formatTimeValue(hour: Int, minute: Int): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/** 解析 HTML `yyyy-MM-ddTHH:mm`（或空格分隔）本地日期时间串。 */
internal fun parseDateTimeLocalValue(raw: String?): WebUiDateTimeValue? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val parts = text.split(dateTimeSplitRegex, limit = 2)
    if (parts.size != 2) return null
    val date = parseDateValue(parts[0]) ?: return null
    val time = parseTimeValue(parts[1]) ?: return null
    return WebUiDateTimeValue(date = date, time = time)
}

/** 将日期与时间格式化为 HTML `<input type="datetime-local">` 要求的 `yyyy-MM-ddTHH:mm`。 */
internal fun formatDateTimeLocalValue(date: WebUiDateValue, time: WebUiTimeValue): String =
    "${formatDateValue(date.year, date.month, date.day)}T${formatTimeValue(time.hour, time.minute)}"

/** 解析 HTML 日期或日期时间字符串中的日期部分，转换为指定时区当天 00:00:00 的毫秒时间戳。 */
internal fun parseDateEpochMillis(
    raw: String?,
    zone: TimeZone = TimeZone.getDefault()
): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val datePart = text.split(dateTimeSplitRegex, limit = 2).first()
    val date = parseDateValue(datePart) ?: return null
    val calendar = Calendar.getInstance(zone, Locale.ROOT).apply {
        clear()
        set(date.year, date.month - 1, date.day, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return calendar.timeInMillis
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if ((year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)) 29 else 28
    else -> 0
}

private val shortHexColorRegex = Regex("""^[0-9a-fA-F]{3}$""")
private val fullHexColorRegex = Regex("""^[0-9a-fA-F]{6}$""")

/**
 * 归一化十六进制颜色值为标准小写 `#rrggbb`：支持省略前导 `#` 以及 3 位简写 `#rgb` 自动展开；
 * 含透明度或非法字符时返回 null，防止将不合规格式回传给 GeckoView `ColorPrompt`。
 */
internal fun normalizeHexColor(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty().removePrefix("#")
    return when {
        fullHexColorRegex.matches(trimmed) -> "#${trimmed.lowercase(Locale.ROOT)}"
        shortHexColorRegex.matches(trimmed) -> {
            val lower = trimmed.lowercase(Locale.ROOT)
            "#${lower[0]}${lower[0]}${lower[1]}${lower[1]}${lower[2]}${lower[2]}"
        }
        else -> null
    }
}

/** `<input type="color">` 弹窗默认提供的 16 色快速色板（覆盖常用高对比与主题色调）。 */
internal val DEFAULT_WEB_UI_COLOR_PALETTE: List<String> = listOf(
    "#000000", "#ffffff", "#64748b", "#ef4444",
    "#f97316", "#f59e0b", "#eab308", "#84cc16",
    "#22c55e", "#14b8a6", "#06b6d4", "#3b82f6",
    "#6366f1", "#8b5cf6", "#d946ef", "#ec4899"
)

/**
 * 判断网页请求权限的来源 URI 是否属于本机回环 HTTP/HTTPS 地址（`127.0.0.1`、`localhost` 或 `::1`），
 * 仅对本机 WebUI 自动放行声音/静音自动播放权限，防止远端任意站点静默出声。
 */
internal fun isLocalLoopbackUri(uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    val uri = runCatching { URI(uriString.trim()) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase(Locale.ROOT) ?: return false
    return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
}
