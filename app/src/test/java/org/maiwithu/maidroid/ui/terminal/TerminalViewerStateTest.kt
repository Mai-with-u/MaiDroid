package org.maiwithu.maidroid.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.maiwithu.maidroid.terminal.AnsiText

class TerminalViewerStateTest {
    private val lines = listOf(
        "[MaiBot] Process started (PID: 1234)",
        "",
        "\u001B[33m10-28 14:00:00\u001B[0m \u001B[38;2;0;175;135m[NapCat内置适配器]\u001B[0m 连接失败"
    ).map(AnsiText::parse)

    private val highlight = SpanStyle(background = Color.Red)

    @Test
    fun `filter matches plain text across color boundaries and keeps source indices`() {
        // 关键字横跨时间戳和模块名两段颜色，中间隔着 ESC[0m 和 ESC[38;2;…m
        val matched = filterTerminalRows(lines, "  14:00:00 [napcat ")

        assertEquals(listOf(TerminalRow(2, lines[2])), matched)
        assertEquals(lines.indices.toList(), filterTerminalRows(lines, " ").map { it.sourceIndex })
    }

    @Test
    fun `display text keeps colors and highlights every match ignoring case`() {
        val text = AnsiText.parse("\u001B[33mNapCat\u001B[0m 连接 napcat 失败").toDisplayText("NAPCAT", highlight)

        assertEquals("NapCat 连接 napcat 失败", text.text)
        assertEquals(
            listOf(Triple(0, 6, Color(0xFFE5E510))),
            text.spanStyles.filter { it.item.color != Color.Unspecified }.map { Triple(it.start, it.end, it.item.color) }
        )
        assertEquals(
            listOf(0 to 6, 10 to 16),
            text.spanStyles.filter { it.item == highlight }.map { it.start to it.end }
        )
    }

    @Test
    fun `blank line is shown as a single space to keep its height`() {
        assertEquals(" ", lines[1].toDisplayText("", highlight).text)
    }

    @Test
    fun `new lines are counted from the frozen last line by identity`() {
        val frozen = listOf(String(charArrayOf('a')), String(charArrayOf('b')))
        // 新来的一行和快照最后一行内容相同，但不是同一个对象，不能被当成快照的末尾。
        val live = frozen + String(charArrayOf('c')) + String(charArrayOf('b'))

        assertEquals(2, countNewLines(frozen, live))
        assertEquals(0, countNewLines(frozen, frozen))
    }

    @Test
    fun `new line count is unknown once the frozen lines are pushed out`() {
        val frozen = listOf("a", "b")
        val live = (1..800).map { "line $it" }

        assertNull(countNewLines(frozen, live))
        assertEquals(3, countNewLines(emptyList(), listOf("x", "y", "z")))
    }

    @Test
    fun `range selection works in both directions and clamps to the list`() {
        val rows = listOf(TerminalRow(3, lines[0]), TerminalRow(5, lines[1]), TerminalRow(9, lines[2]))

        assertEquals(setOf(5, 9), rangeSelection(rows, 2, 1))
        assertEquals(setOf(3, 5, 9), rangeSelection(rows, -4, 10))
        assertEquals(emptySet<Int>(), rangeSelection(emptyList(), 0, 3))
    }

    @Test
    fun `row index falls forward to the next shown row`() {
        val rows = listOf(TerminalRow(3, lines[0]), TerminalRow(5, lines[1]), TerminalRow(9, lines[2]))

        assertEquals(1, rowIndexAtOrAfter(rows, 5))
        assertEquals(2, rowIndexAtOrAfter(rows, 6))
        assertEquals(0, rowIndexAtOrAfter(rows, 0))
        assertEquals(2, rowIndexAtOrAfter(rows, 42))
        assertNull(rowIndexAtOrAfter(emptyList(), 1))
    }

    @Test
    fun `selected text follows log order without escape sequences`() {
        assertEquals(
            "[MaiBot] Process started (PID: 1234)\n10-28 14:00:00 [NapCat内置适配器] 连接失败",
            selectedText(lines, setOf(2, 0, 7))
        )
    }

    @Test
    fun `parsed line cache reuses results for unchanged lines`() {
        val cache = ParsedLineCache()
        val raw = "\u001B[31mred\u001B[0m"
        val first = cache.parse(listOf(raw, "plain"))
        val second = cache.parse(listOf(raw, "plain", "new"))

        assertSame(first[0], second[0])
        assertEquals("new", second[2].text)
    }
}
