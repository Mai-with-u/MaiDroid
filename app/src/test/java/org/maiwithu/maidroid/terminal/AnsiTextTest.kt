package org.maiwithu.maidroid.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTextTest {
    private val yellow = 0xFFE5E510.toInt()
    private val cyan = 0xFF11A8CD.toInt()
    private val teal = 0xFF00AF87.toInt()

    // 真机 logcat 里抓到的 MaiBot 输出：交互提示符先重绘 "> "，再退格擦掉，然后才是带颜色的日志正文。
    private val maiBotPromptLine = "\u001B[?2004h\u001B[?25l\u001B[0m\u001B[?7l\u001B[0m\u001B[J\u001B[0;36;1m> " +
        "\u001B[2D\u001B[2C\u001B[?7h\u001B[0m\u001B[?12l\u001B[?25h\u001B[2D\u001B[J\u001B[0m\u001B[?7h" +
        "\u001B[?2004l\u001B[?7h\u001B[33m10-28 14:00:00\u001B[0m \u001B[38;2;0;175;135m[NapCat内置适配器]\u001B[0m " +
        "\u001B[38;2;0;175;135mNapCat 适配器连接失败；将在 5 秒后重连\u001B[0m"

    @Test
    fun `maibot prompt redraw leaves only the colored log text`() {
        val line = AnsiText.parse(maiBotPromptLine)

        // 提示符 "> " 被 ESC[2D + ESC[J 擦掉，不能残留在行首
        assertEquals("10-28 14:00:00 [NapCat内置适配器] NapCat 适配器连接失败；将在 5 秒后重连", line.text)
        assertEquals(
            listOf(
                AnsiSpan(0, 14, AnsiStyle(foreground = yellow)),
                AnsiSpan(15, 28, AnsiStyle(foreground = teal)),
                AnsiSpan(29, line.text.length, AnsiStyle(foreground = teal))
            ),
            line.spans
        )
    }

    @Test
    fun `private mode and cursor sequences are dropped`() {
        listOf("\u001B[?2004h", "\u001B[?25l", "\u001B[J", "\u001B[2D", "\u001B[?7l", "\u001B[?2004l", "\u001B[?25h")
            .forEach { sequence ->
                val line = AnsiText.parse("${sequence}MaiBot 启动")
                assertEquals("MaiBot 启动", line.text)
                assertTrue(line.spans.isEmpty())
            }
    }

    @Test
    fun `sgr colors and bold become spans`() {
        val line = AnsiText.parse("\u001B[33mWARN\u001B[0m \u001B[0;36;1mprompt\u001B[0m done")

        assertEquals("WARN prompt done", line.text)
        assertEquals(
            listOf(
                AnsiSpan(0, 4, AnsiStyle(foreground = yellow)),
                AnsiSpan(5, 11, AnsiStyle(foreground = cyan, bold = true))
            ),
            line.spans
        )
    }

    @Test
    fun `extended and bright colors are resolved`() {
        val line = AnsiText.parse(
            "\u001B[38;5;196mA\u001B[48;5;244mB\u001B[0m\u001B[91;4mC\u001B[24;39;102mD\u001B[49;3mE\u001B[m"
        )

        assertEquals("ABCDE", line.text)
        assertEquals(
            listOf(
                AnsiSpan(0, 1, AnsiStyle(foreground = 0xFFFF0000.toInt())),
                AnsiSpan(1, 2, AnsiStyle(foreground = 0xFFFF0000.toInt(), background = 0xFF808080.toInt())),
                AnsiSpan(2, 3, AnsiStyle(foreground = 0xFFF14C4C.toInt(), underline = true)),
                AnsiSpan(3, 4, AnsiStyle(background = 0xFF23D18B.toInt())),
                AnsiSpan(4, 5, AnsiStyle(italic = true))
            ),
            line.spans
        )
    }

    @Test
    fun `carriage return and backspace overwrite like a terminal`() {
        assertEquals("100%", AnsiText.plain("50%\r100%"))
        assertEquals("ab", AnsiText.plain("ax\bb"))
        // 行内的 CRLF 不能把上一行的内容覆盖掉
        assertEquals("first\nsecond", AnsiText.plain("first\r\nsecond"))
    }

    @Test
    fun `erase line modes and column moves`() {
        assertEquals("hello", AnsiText.plain("hello world\u001B[6D\u001B[K"))
        // ESC[1K 从行首擦到光标所在的字符（含），擦掉的位置留空格
        assertEquals("    o", AnsiText.plain("hello\u001B[2D\u001B[1K"))
        assertEquals("new", AnsiText.plain("old\u001B[2K\u001B[1Gnew"))
        assertEquals("a  b", AnsiText.plain("a\u001B[2Cb"))
        // 超大的右移被限制住，不会补出成千上万个空格
        assertEquals(513, AnsiText.plain("\u001B[9999Cx").length)
    }

    @Test
    fun `osc charset and keypad sequences are dropped`() {
        assertEquals("text", AnsiText.plain("\u001B]0;MaiBot\u0007text"))
        assertEquals("text", AnsiText.plain("\u001B]2;title\u001B\\text"))
        assertEquals("text", AnsiText.plain("\u001B(Btext\u001B="))
        assertEquals("text", AnsiText.plain("te\u0007xt\u007F"))
    }

    @Test
    fun `truncated sequences do not leak into text`() {
        assertEquals("text", AnsiText.plain("text\u001B"))
        assertEquals("text", AnsiText.plain("text\u001B[38;2;0"))
        assertEquals("text", AnsiText.plain("text\u001B]0;unterminated title"))
        // 冒号写法的子参数不展开，但也不能出错或漏出文字
        assertEquals(AnsiLine("X"), AnsiText.parse("\u001B[38:2:0:175:135mX"))
    }

    @Test
    fun `plain lines are returned untouched`() {
        val raw = "[MaiBot] Process started (PID: 1234)\tok"
        val line = AnsiText.parse(raw)

        assertSame(raw, line.text)
        assertSame(raw, line.toSgrString())
    }

    @Test
    fun `sgr string keeps only colors and parses back to the same line`() {
        val parsed = AnsiText.parse(maiBotPromptLine)
        val stored = parsed.toSgrString()

        // 入库的行里只剩 ESC[…m，光标、私有模式序列都没了
        assertFalse(Regex("\u001B\\[[0-9;]*[^0-9;m]").containsMatchIn(stored))
        assertFalse(stored.contains("\u001B[?"))
        assertEquals(parsed, AnsiText.parse(stored))
        assertEquals(parsed.text, AnsiText.plain(stored))
    }

    @Test
    fun `colors continue across embedded newlines`() {
        val line = AnsiText.parse("\u001B[31mline1\nline2\u001B[0m")

        assertEquals("line1\nline2", line.text)
        assertEquals(listOf(0 until 5, 6 until 11), line.spans.map { it.start until it.end })
        assertEquals(line, AnsiText.parse(line.toSgrString()))
    }
}
