package org.maiwithu.maidroid.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.terminal.AnsiLine
import org.maiwithu.maidroid.terminal.AnsiStyle
import org.maiwithu.maidroid.terminal.AnsiText

/**
 * 终端输出查看器的交互状态：筛选关键字、是否自动跟随最新输出、是否自动换行，以及日志区和工具栏的滚动位置。
 * 全屏弹窗（TerminalOutputDialog）和 OOBE 平板布局里的嵌入面板共用同一套行为。
 */
@Stable
internal class TerminalOutputState(
    private val coroutineScope: CoroutineScope,
    val verticalScroll: ScrollState,
    val horizontalScroll: ScrollState,
    val toolbarScroll: ScrollState,
    queryState: MutableState<String>,
    autoFollowState: MutableState<Boolean>,
    wrapLinesState: MutableState<Boolean>
) {
    var query by queryState
    var autoFollow by autoFollowState
        private set
    var wrapLines by wrapLinesState
        private set

    /** 打开自动跟随时立刻滚到最新一行。 */
    fun changeAutoFollow(enabled: Boolean) {
        autoFollow = enabled
        if (enabled) scrollToLatest()
    }

    /** 打开自动换行时把横向滚动复位，否则长行换行后仍停在中间。 */
    fun changeWrapLines(enabled: Boolean) {
        wrapLines = enabled
        if (enabled) coroutineScope.launch { horizontalScroll.scrollTo(0) }
    }

    /** 右下角“跳到最新输出”：重新开始自动跟随。 */
    fun jumpToLatest() {
        autoFollow = true
        scrollToLatest()
    }

    /** 用户手动往上翻时暂停自动跟随，免得新日志把他拽回底部。 */
    fun pauseFollow() {
        autoFollow = false
    }

    private fun scrollToLatest() {
        coroutineScope.launch { verticalScroll.animateScrollTo(verticalScroll.maxValue) }
    }
}

/** 筛选关键字、跟随、换行三个开关在屏幕旋转后保留。 */
@Composable
internal fun rememberTerminalOutputState(): TerminalOutputState {
    val coroutineScope = rememberCoroutineScope()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val toolbarScroll = rememberScrollState()
    val query = rememberSaveable { mutableStateOf("") }
    val autoFollow = rememberSaveable { mutableStateOf(true) }
    val wrapLines = rememberSaveable { mutableStateOf(true) }
    return remember {
        TerminalOutputState(coroutineScope, verticalScroll, horizontalScroll, toolbarScroll, query, autoFollow, wrapLines)
    }
}

/**
 * 终端面板要显示的行：先解析转义序列得到纯文本和颜色，再按关键字筛选。
 * TerminalLog 入库时已经执行过光标控制，这里主要是解析颜色；OOBE 的命令日志没经过 TerminalLog，原始序列也在这里一并处理。
 * 解析只在日志变化时做一次，输入筛选关键字时不重复解析。
 */
@Composable
internal fun rememberDisplayedLines(logs: List<String>, query: String): List<AnsiLine> {
    val parsedLines = remember(logs) { logs.map(AnsiText::parse) }
    return remember(parsedLines, query) { filterTerminalLines(parsedLines, query) }
}

/** 按关键字（忽略大小写）筛出要显示的行；关键字为空时显示全部。匹配的是纯文本，关键字跨过颜色边界也能找到。 */
internal fun filterTerminalLines(lines: List<AnsiLine>, query: String): List<AnsiLine> {
    val keyword = query.trim()
    return if (keyword.isEmpty()) lines else lines.filter { line -> line.text.contains(keyword, ignoreCase = true) }
}

/** 把行拼成一段带颜色的文本交给可选择的 Text 显示；空行补一个空格，保持空行的行高。 */
internal fun List<AnsiLine>.toTerminalText(): AnnotatedString = buildAnnotatedString {
    this@toTerminalText.forEachIndexed { index, line ->
        if (index > 0) append('\n')
        if (line.text.isBlank()) {
            append(' ')
        } else {
            val offset = length
            append(line.text)
            line.spans.forEach { span ->
                addStyle(span.style.toSpanStyle(), offset + span.start, offset + span.end)
            }
        }
    }
}

// SGR 样式换成 Compose 的 SpanStyle；没设颜色的部分沿用面板默认的浅灰字和近黑底。
private fun AnsiStyle.toSpanStyle(): SpanStyle = SpanStyle(
    color = foreground?.let { Color(it) } ?: Color.Unspecified,
    background = background?.let { Color(it) } ?: Color.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (underline) TextDecoration.Underline else null
)

/** 自动跟随开启时，内容一变化（新日志、切换换行）就滚到最底部；稍等 40 毫秒让新内容先完成排版。 */
@Composable
internal fun FollowLatestOutputEffect(viewer: TerminalOutputState, terminalText: AnnotatedString) {
    LaunchedEffect(terminalText, viewer.autoFollow, viewer.wrapLines) {
        if (viewer.autoFollow && terminalText.isNotEmpty()) {
            delay(40)
            viewer.verticalScroll.scrollTo(viewer.verticalScroll.maxValue)
        }
    }
}

/** 复制当前显示（已筛选）的输出到剪贴板，并提示复制了几行。复制的是纯文本，不带颜色序列。 */
internal fun copyTerminalLines(context: Context, lines: List<AnsiLine>) {
    if (lines.isEmpty()) return
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(
        ClipData.newPlainText("MaiDroid 终端输出", lines.joinToString(separator = "\n") { line -> line.text })
    )
    Toast.makeText(context, "已复制 ${lines.size} 行输出", Toast.LENGTH_SHORT).show()
}
