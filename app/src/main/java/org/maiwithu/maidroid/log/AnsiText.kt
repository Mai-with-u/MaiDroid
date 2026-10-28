package org.maiwithu.maidroid.log

/** 终端里一段文字的样式，由 SGR 序列（ESC[…m）决定。颜色是 0xAARRGGBB，null 表示用终端面板的默认色。 */
data class AnsiStyle(
    val foreground: Int? = null,
    val background: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
) {
    // 重新编码成一条 SGR 序列；只在默认样式之上叠加，所以调用方要保证此前已经复位过样式。
    internal fun toSgr(): String = buildList {
        if (bold) add("1")
        if (italic) add("3")
        if (underline) add("4")
        foreground?.let { add("38;2;${it.red()};${it.green()};${it.blue()}") }
        background?.let { add("48;2;${it.red()};${it.green()};${it.blue()}") }
    }.joinToString(separator = ";", prefix = "$ESC[", postfix = "m")

    companion object {
        val Default = AnsiStyle()
    }
}

/** 纯文本里 [start, end) 这一段的样式。 */
data class AnsiSpan(val start: Int, val end: Int, val style: AnsiStyle)

/** 按终端语义还原后的一行：text 不含任何转义序列，spans 只列出非默认样式的区段，按位置排好且互不重叠。 */
data class AnsiLine(val text: String, val spans: List<AnsiSpan> = emptyList()) {
    /**
     * 重新编码成只含 SGR 颜色序列的字符串，TerminalLog 入库存的就是这种形式：
     * 每个区段前写一条完整样式、区段后复位，再解析一遍得到的还是同一个 AnsiLine。
     */
    fun toSgrString(): String {
        if (spans.isEmpty()) return text
        val out = StringBuilder(text.length + spans.size * 24)
        var position = 0
        spans.forEach { span ->
            out.append(text, position, span.start)
                .append(span.style.toSgr())
                .append(text, span.start, span.end)
                .append(SGR_RESET)
            position = span.end
        }
        return out.append(text, position, text.length).toString()
    }
}

/**
 * 解析容器进程输出里的 ANSI 转义序列。
 *
 * MaiBot 的交互提示符每打一行日志都要重绘一次：开关括号粘贴模式（ESC[?2004h/l）、隐藏和显示光标（ESC[?25l/h），
 * 画出带颜色的 "> " 后用 ESC[2D 退两格、ESC[J 擦掉，再输出日志正文。只删序列不执行的话，每行开头都会残留 "> "，
 * 所以这里把一行日志当成终端里的一行来执行：回车、退格、光标左右移动和擦除按终端语义处理，SGR 解析成颜色和字形，
 * 其余控制序列直接丢弃。光标上下移动、滚屏这类跨行操作对单行没有意义，一律忽略；列宽按一个字符一列算，不区分全角。
 */
object AnsiText {
    /** 解析一行原始输出，得到纯文本和颜色区段。 */
    fun parse(raw: String): AnsiLine {
        // 绝大多数行（应用自己写的提示、没开颜色的命令输出）没有控制字符，原样返回，省掉逐字处理。
        if (raw.none(::isControl)) return AnsiLine(raw)

        val text = StringBuilder(raw.length)
        val styles = ArrayList<AnsiStyle>(raw.length)
        var style = AnsiStyle.Default
        // 行内夹带的换行（比如多行的异常信息）各按终端里的一行执行，颜色延续到下一行，和真实终端一致。
        raw.split('\n').forEachIndexed { index, segment ->
            if (index > 0) {
                text.append('\n')
                styles.add(AnsiStyle.Default)
            }
            val row = TerminalRow(style)
            row.draw(segment)
            style = row.style
            text.append(row.chars)
            styles.addAll(row.styles)
        }
        return AnsiLine(text.toString(), styles.toSpans())
    }

    /** 去掉全部转义序列后的纯文本：筛选、令牌提取、按日志判断状态时都用它，免得关键字被颜色代码打断。 */
    fun plain(raw: String): String = parse(raw).text
}

private const val ESC = '\u001B'
private const val BEL = '\u0007'
private const val SGR_RESET = "$ESC[0m"

// 光标右移、跳列的上限：防止 ESC[9999C 这类“移到行尾”的写法让下一笔输出补出成千上万个空格。
private const val MAX_COLUMN = 512

// 换行和制表符当普通文字保留，其余 C0/C1 控制字符和 DEL 都要执行或丢弃。
private fun isControl(c: Char): Boolean = (c < ' ' && c != '\n' && c != '\t') || c in '\u007F'..'\u009F'

/** 单行终端：按光标位置覆盖写入，同时记下每个字符写入时的样式。 */
private class TerminalRow(var style: AnsiStyle) {
    val chars = StringBuilder()
    val styles = ArrayList<AnsiStyle>()
    private var cursor = 0

    fun draw(segment: String) {
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                c == ESC -> {
                    i = handleEscape(segment, i)
                    continue
                }
                // 回车回到行首，后面的输出覆盖旧内容（进度条就是这样刷新同一行的）。
                c == '\r' -> cursor = 0
                c == '\b' -> cursor = (cursor - 1).coerceAtLeast(0)
                isControl(c) -> Unit
                else -> put(c)
            }
            i++
        }
    }

    // 从 ESC 开始识别一个转义序列，执行完返回序列之后的位置；序列被截断时把剩下的部分一起吞掉。
    private fun handleEscape(s: String, start: Int): Int {
        val next = s.getOrNull(start + 1) ?: return s.length
        return when (next) {
            '[' -> handleCsi(s, start + 2)
            // OSC（设置窗口标题等）和 DCS、SOS、PM、APC 都是字符串型序列，到 BEL 或 ESC\ 为止整段丢弃。
            ']', 'P', 'X', '^', '_' -> skipStringSequence(s, start + 2)
            // 字符集切换（ESC ( B）这类：若干个中间字节再跟一个结束字节。
            in ' '..'/' -> {
                var j = start + 1
                while (j < s.length && s[j] in ' '..'/') j++
                if (j < s.length && s[j] in '0'..'~') j + 1 else j
            }
            // 保存、恢复光标和切换小键盘模式（ESC 7、ESC 8、ESC =）这类两字节序列。
            in '0'..'~' -> start + 2
            else -> start + 1
        }
    }

    private fun handleCsi(s: String, paramsStart: Int): Int {
        var j = paramsStart
        // 参数字节（0x30–0x3F）和中间字节（0x20–0x2F）都在这个范围里，结束字节在 0x40–0x7E。
        while (j < s.length && s[j] in ' '..'?') j++
        if (j >= s.length) return s.length
        // 结束字节不合规说明不是完整的序列：丢掉已读的部分，从这个字符起按普通文字处理。
        if (s[j] !in '@'..'~') return j
        val params = s.substring(paramsStart, j)
        // 以 ?、<、=、> 开头的私有模式（括号粘贴、显隐光标、自动换行开关）和带中间字节的序列不影响显示，直接丢弃。
        if (params.all { it in '0'..'9' || it == ';' || it == ':' }) execute(s[j], params)
        return j + 1
    }

    private fun execute(command: Char, params: String) {
        when (command) {
            'm' -> style = style.applySgr(params)
            'D' -> cursor = (cursor - params.moveCount()).coerceAtLeast(0)
            'C' -> cursor = (cursor + params.moveCount()).coerceAtMost(MAX_COLUMN)
            'G' -> cursor = (params.moveCount() - 1).coerceAtMost(MAX_COLUMN)
            // 擦屏（J）落到单行上和擦行（K）是一回事：0 从光标擦到行尾，1 从行首擦到光标，2、3 整行擦掉。
            'K', 'J' -> erase(params.firstParam() ?: 0)
        }
    }

    private fun erase(mode: Int) {
        when (mode) {
            0 -> if (cursor < chars.length) {
                chars.setLength(cursor)
                styles.subList(cursor, styles.size).clear()
            }
            1 -> for (k in 0 until minOf(cursor + 1, chars.length)) {
                chars.setCharAt(k, ' ')
                styles[k] = AnsiStyle.Default
            }
            2, 3 -> {
                chars.setLength(0)
                styles.clear()
            }
        }
    }

    // 光标停在已有内容中间就覆盖，跑到行尾之后就先用空格补齐再写。
    private fun put(c: Char) {
        while (chars.length < cursor) {
            chars.append(' ')
            styles.add(AnsiStyle.Default)
        }
        if (cursor < chars.length) {
            chars.setCharAt(cursor, c)
            styles[cursor] = style
        } else {
            chars.append(c)
            styles.add(style)
        }
        cursor++
    }

    // 字符串型序列以 BEL 或 ESC\ 结尾；中途遇到别的 ESC 就在那里结束，让它按新序列处理。
    // 一直找不到结尾说明序列被拆到了下一行，剩下的都当序列内容丢掉。
    private fun skipStringSequence(s: String, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == BEL) return j + 1
            if (s[j] == ESC) return if (s.getOrNull(j + 1) == '\\') j + 2 else j
            j++
        }
        return s.length
    }
}

private fun String.firstParam(): Int? = substringBefore(';').toIntOrNull()

// 光标移动的步数和列号：缺省或写 0 都按 1 处理，和 xterm 一致。
private fun String.moveCount(): Int = firstParam()?.coerceAtLeast(1) ?: 1

// 依次套用 SGR 参数。空参数等于 0（复位）；冒号写法的子参数（38:2::r:g:b）不展开，整个参数当未知代码跳过。
private fun AnsiStyle.applySgr(params: String): AnsiStyle {
    val codes = params.split(';').map { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }
    var result = this
    var i = 0
    while (i < codes.size) {
        when (val code = codes[i]) {
            0 -> result = AnsiStyle.Default
            1 -> result = result.copy(bold = true)
            3 -> result = result.copy(italic = true)
            4 -> result = result.copy(underline = true)
            22 -> result = result.copy(bold = false)
            23 -> result = result.copy(italic = false)
            24 -> result = result.copy(underline = false)
            in 30..37 -> result = result.copy(foreground = ANSI_PALETTE[code - 30])
            in 90..97 -> result = result.copy(foreground = ANSI_PALETTE[code - 90 + 8])
            39 -> result = result.copy(foreground = null)
            in 40..47 -> result = result.copy(background = ANSI_PALETTE[code - 40])
            in 100..107 -> result = result.copy(background = ANSI_PALETTE[code - 100 + 8])
            49 -> result = result.copy(background = null)
            38, 48 -> {
                val (color, used) = extendedColor(codes, i + 1)
                if (color != null) {
                    result = if (code == 38) result.copy(foreground = color) else result.copy(background = color)
                }
                i += used
            }
        }
        i++
    }
    return result
}

// 38/48 后面的扩展颜色：5;n 取 256 色表，2;r;g;b 是真彩色。返回颜色和占用的参数个数，参数不全时把剩下的都算作占用。
private fun extendedColor(codes: List<Int>, from: Int): Pair<Int?, Int> {
    val remaining = codes.size - from
    return when (codes.getOrNull(from)) {
        5 -> if (remaining >= 2 && codes[from + 1] in 0..255) xterm256(codes[from + 1]) to 2 else null to minOf(2, remaining)
        2 -> if (remaining >= 4) rgb(codes[from + 1], codes[from + 2], codes[from + 3]) to 4 else null to remaining
        else -> null to 0
    }
}

// 256 色表：0–15 是基本 16 色，16–231 是 6×6×6 色立方，232–255 是 24 级灰。
private fun xterm256(index: Int): Int = when {
    index < 16 -> ANSI_PALETTE[index]
    index < 232 -> {
        val cube = index - 16
        rgb(cubeLevel(cube / 36), cubeLevel(cube / 6 % 6), cubeLevel(cube % 6))
    }
    else -> (8 + (index - 232) * 10).let { gray -> rgb(gray, gray, gray) }
}

private fun cubeLevel(step: Int): Int = if (step == 0) 0 else 55 + step * 40

private fun rgb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

private fun Int.red(): Int = (this shr 16) and 0xFF
private fun Int.green(): Int = (this shr 8) and 0xFF
private fun Int.blue(): Int = this and 0xFF

// 基本 16 色，按终端面板的近黑底色调过：黑色调成深灰，免得 ESC[30m 的字完全看不见。
private val ANSI_PALETTE = intArrayOf(
    rgb(0x5C, 0x5C, 0x63), rgb(0xCD, 0x31, 0x31), rgb(0x0D, 0xBC, 0x79), rgb(0xE5, 0xE5, 0x10),
    rgb(0x24, 0x72, 0xC8), rgb(0xBC, 0x3F, 0xBC), rgb(0x11, 0xA8, 0xCD), rgb(0xE5, 0xE5, 0xE5),
    rgb(0x80, 0x80, 0x87), rgb(0xF1, 0x4C, 0x4C), rgb(0x23, 0xD1, 0x8B), rgb(0xF5, 0xF5, 0x43),
    rgb(0x3B, 0x8E, 0xEA), rgb(0xD6, 0x70, 0xD6), rgb(0x29, 0xB8, 0xDB), rgb(0xFF, 0xFF, 0xFF)
)

// 把逐字样式合并成区段，默认样式的部分不记。
private fun List<AnsiStyle>.toSpans(): List<AnsiSpan> {
    val spans = mutableListOf<AnsiSpan>()
    var start = 0
    for (i in 1..size) {
        if (i == size || this[i] != this[start]) {
            if (this[start] != AnsiStyle.Default) spans += AnsiSpan(start, i, this[start])
            start = i
        }
    }
    return spans
}
