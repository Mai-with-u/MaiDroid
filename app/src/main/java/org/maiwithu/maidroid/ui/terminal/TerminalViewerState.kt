package org.maiwithu.maidroid.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.maiwithu.maidroid.terminal.AnsiLine
import org.maiwithu.maidroid.terminal.AnsiStyle
import org.maiwithu.maidroid.terminal.AnsiText

/**
 * 终端输出查看器的交互状态，全屏终端页和 OOBE 平板布局里的嵌入面板共用。
 *
 * 两种模式：
 * - 实时：显示最新日志并一直停在最底部；
 * - 暂停：把进入暂停那一刻的日志冻结成快照，之后的新日志只计数、不上屏。往上翻、长按选行、点暂停按钮都会进入暂停，
 *   这样阅读和选择的时候内容不会被新日志顶走（TerminalLog 满 800 行后每来一行都会从顶部删一行，不冻结的话整屏都会跟着挪）。
 *   点“新输出”提示、点继续按钮、或者不在选择时手动滑回最底部，回到实时。
 *
 * 选择：长按某行进入选择模式，按住拖动连选一段，单击增减单行；选择期间一直保持暂停。
 * 选中的行记的是快照里的下标，快照冻结期间下标不会变。
 */
@Stable
internal class TerminalViewerState(
    val listState: LazyListState,
    queryState: MutableState<String>,
    searchOpenState: MutableState<Boolean>,
    wrapLinesState: MutableState<Boolean>
) {
    var query by queryState
    var searchOpen by searchOpenState
        private set
    var wrapLines by wrapLinesState
        private set

    /** 暂停时冻结的日志快照；null 表示实时模式。 */
    var frozenLogs by mutableStateOf<List<String>?>(null)
        private set
    val paused: Boolean get() = frozenLogs != null

    /** 选中的行（快照里的下标）；为空表示不在选择模式。 */
    var selection by mutableStateOf<Set<Int>>(emptySet())
        private set
    val selecting: Boolean get() = selection.isNotEmpty()

    // 长按拖动连选：按下时已有的选择 + 锚点所在的行，拖动时用锚点到手指所在行这一段并上已有的选择。
    private var dragBase: Set<Int> = emptySet()
    private var dragAnchor: Int = -1

    /** 当前要显示的日志：暂停时是快照，实时时是传进来的最新日志。 */
    fun shownLogs(liveLogs: List<String>): List<String> = frozenLogs ?: liveLogs

    /** 冻结当前显示的日志。已经暂停时不重复冻结，免得快照被换成更新的日志。 */
    fun pause(liveLogs: List<String>) {
        if (frozenLogs == null) frozenLogs = liveLogs
    }

    /** 回到实时：丢掉快照和选择，由 FollowLatestEffect 滚到最底部。 */
    fun resume() {
        selection = emptySet()
        frozenLogs = null
    }

    fun togglePause(liveLogs: List<String>) {
        if (paused) resume() else pause(liveLogs)
    }

    /** 不在选择时手动滑到了最底部：等同于回到实时，和聊天软件“滑到底就继续跟随”一致。 */
    fun onUserReachedLatest() {
        if (paused && !selecting) resume()
    }

    fun openSearch() {
        searchOpen = true
    }

    /** 收起搜索栏时一并清空筛选，回到完整日志。 */
    fun closeSearch() {
        searchOpen = false
        query = ""
    }

    fun changeWrapLines(enabled: Boolean) {
        wrapLines = enabled
    }

    /** 长按某行：先冻结日志，再以这一行为锚点开始连选（已在选择模式时在原有选择上追加）。 */
    fun startDragSelection(liveLogs: List<String>, rows: List<TerminalRow>, rowIndex: Int) {
        val row = rows.getOrNull(rowIndex) ?: return
        pause(liveLogs)
        dragBase = selection
        dragAnchor = rowIndex
        selection = dragBase + row.sourceIndex
    }

    /** 拖动到第 rowIndex 行（列表里的显示位置）：选中锚点到这一行之间的所有行。 */
    fun extendDragSelection(rows: List<TerminalRow>, rowIndex: Int) {
        if (dragAnchor < 0) return
        selection = dragBase + rangeSelection(rows, dragAnchor, rowIndex)
    }

    fun endDragSelection() {
        dragAnchor = -1
        dragBase = emptySet()
    }

    /** 选择模式下单击某行：选中或取消这一行；取消到一行都不剩时退出选择模式（仍保持暂停）。 */
    fun toggleRow(row: TerminalRow) {
        selection = if (row.sourceIndex in selection) selection - row.sourceIndex else selection + row.sourceIndex
    }

    fun selectAll(rows: List<TerminalRow>) {
        selection = rows.mapTo(HashSet()) { it.sourceIndex }
    }

    fun clearSelection() {
        selection = emptySet()
        endDragSelection()
    }
}

/** 筛选关键字、是否展开搜索栏、是否自动换行在屏幕旋转后保留；暂停和选择不保留，旋转后回到实时。 */
@Composable
internal fun rememberTerminalViewerState(): TerminalViewerState {
    val listState = rememberLazyListState()
    val query = rememberSaveable { mutableStateOf("") }
    val searchOpen = rememberSaveable { mutableStateOf(false) }
    val wrapLines = rememberSaveable { mutableStateOf(true) }
    return remember { TerminalViewerState(listState, query, searchOpen, wrapLines) }
}

/** 列表里的一行：sourceIndex 是它在（快照或实时）日志里的下标，选择和列表的 key 都用它。 */
internal data class TerminalRow(val sourceIndex: Int, val line: AnsiLine)

/**
 * 解析日志里的颜色序列。同一行文字只解析一次：每来一行新日志，列表里其余 799 行直接复用上一次的结果。
 * TerminalLog 入库时已经执行过光标控制，这里主要是解析颜色；OOBE 的命令日志没经过 TerminalLog，原始序列也在这里一并处理。
 */
@Composable
internal fun rememberParsedLines(logs: List<String>): List<AnsiLine> {
    val cache = remember { ParsedLineCache() }
    return remember(logs) { cache.parse(logs) }
}

/** 按文字缓存解析结果；每次只保留当前这批日志用到的条目，缓存不会无限增长。 */
internal class ParsedLineCache {
    private var previous: Map<String, AnsiLine> = emptyMap()

    fun parse(logs: List<String>): List<AnsiLine> {
        val next = HashMap<String, AnsiLine>(logs.size * 2)
        val lines = logs.map { raw -> next.getOrPut(raw) { previous[raw] ?: AnsiText.parse(raw) } }
        previous = next
        return lines
    }
}

/** 按关键字（忽略大小写）筛出要显示的行，保留每行在日志里的下标；关键字为空时显示全部。匹配的是纯文本，关键字跨过颜色边界也能找到。 */
internal fun filterTerminalRows(lines: List<AnsiLine>, query: String): List<TerminalRow> {
    val keyword = query.trim()
    return lines.mapIndexedNotNull { index, line ->
        if (keyword.isEmpty() || line.text.contains(keyword, ignoreCase = true)) TerminalRow(index, line) else null
    }
}

/**
 * 暂停以来来了多少行新日志。日志列表满 800 行后只保留最后 800 行，单看行数差算不出来，
 * 所以在最新日志里从后往前找快照的最后一行：TerminalLog 和 OOBE 的命令日志都是在旧列表后面追加再截断，
 * 同一行始终是同一个字符串对象，按引用（===）找，内容相同但分别打出来的行不会混淆。找不到说明快照里的行已经全被挤掉，返回 null。
 * 只有同一个字符串常量被原样追加了两次时会找到后一次，计数偏少，不影响显示的内容。
 */
internal fun countNewLines(frozen: List<String>, live: List<String>): Int? {
    if (frozen.isEmpty()) return live.size
    val last = frozen.last()
    val index = live.indexOfLast { it === last }
    return if (index < 0) null else live.size - 1 - index
}

/** 日志下标为 sourceIndex 的行在 rows 里的位置；这一行被筛掉了就取它后面最近的一行，都没有就取最后一行。rows 按日志顺序排列。 */
internal fun rowIndexAtOrAfter(rows: List<TerminalRow>, sourceIndex: Int): Int? {
    if (rows.isEmpty()) return null
    val found = rows.binarySearch { it.sourceIndex.compareTo(sourceIndex) }
    return if (found >= 0) found else (-found - 1).coerceAtMost(rows.lastIndex)
}

/** 列表里第 from 行到第 to 行（含两端，顺序不限）对应的日志下标。 */
internal fun rangeSelection(rows: List<TerminalRow>, from: Int, to: Int): Set<Int> {
    if (rows.isEmpty()) return emptySet()
    val start = minOf(from, to).coerceIn(0, rows.lastIndex)
    val end = maxOf(from, to).coerceIn(0, rows.lastIndex)
    return (start..end).mapTo(HashSet()) { rows[it].sourceIndex }
}

/** 要复制的纯文本：选中的行按日志里的先后顺序拼起来，不带颜色序列。 */
internal fun selectedText(lines: List<AnsiLine>, selection: Set<Int>): String =
    selection.sorted().mapNotNull { lines.getOrNull(it)?.text }.joinToString(separator = "\n")

/**
 * 一行日志的显示文字：SGR 颜色换成 SpanStyle，再给命中筛选关键字的位置（忽略大小写）加上高亮底色。
 * 空行补一个空格，保持空行的行高。
 */
internal fun AnsiLine.toDisplayText(query: String, highlight: SpanStyle): AnnotatedString = buildAnnotatedString {
    if (text.isBlank()) {
        append(' ')
        return@buildAnnotatedString
    }
    append(text)
    spans.forEach { span -> addStyle(span.style.toSpanStyle(), span.start, span.end) }
    val keyword = query.trim()
    if (keyword.isNotEmpty()) {
        var start = text.indexOf(keyword, ignoreCase = true)
        while (start >= 0) {
            addStyle(highlight, start, start + keyword.length)
            start = text.indexOf(keyword, start + keyword.length, ignoreCase = true)
        }
    }
}

// SGR 样式换成 Compose 的 SpanStyle；没设颜色的部分沿用终端的默认浅灰字和近黑底。
private fun AnsiStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = foreground?.let { Color(it) } ?: Color.Unspecified,
    background = background?.let { Color(it) } ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (underline) TextDecoration.Underline else null
)

/** 把文字放进剪贴板并提示复制了几行；hint 不为空时接在提示后面（比如告诉用户可以长按只复制几行）。 */
internal fun copyTerminalText(context: Context, text: String, lineCount: Int, hint: String? = null) {
    if (lineCount <= 0) return
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("MaiDroid 终端输出", text))
    val message = if (hint == null) "已复制 $lineCount 行输出" else "已复制 $lineCount 行输出，$hint"
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
