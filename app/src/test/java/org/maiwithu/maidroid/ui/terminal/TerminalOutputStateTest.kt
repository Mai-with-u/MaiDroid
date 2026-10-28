package org.maiwithu.maidroid.ui.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.maiwithu.maidroid.terminal.AnsiText

class TerminalOutputStateTest {
    private val lines = listOf(
        "[MaiBot] Process started (PID: 1234)",
        "",
        "\u001B[33m10-28 14:00:00\u001B[0m \u001B[38;2;0;175;135m[NapCat内置适配器]\u001B[0m 连接失败"
    ).map(AnsiText::parse)

    @Test
    fun `filter matches plain text across color boundaries`() {
        // 关键字横跨时间戳和模块名两段颜色，中间隔着 ESC[0m 和 ESC[38;2;…m
        val matched = filterTerminalLines(lines, "  14:00:00 [napcat ")

        assertEquals(listOf(lines[2]), matched)
        assertEquals(lines, filterTerminalLines(lines, " "))
    }

    @Test
    fun `terminal text keeps colors at joined offsets`() {
        val text = lines.toTerminalText()

        // 空行补一个空格；第三行的颜色区段要按拼接后的位置偏移
        assertEquals("[MaiBot] Process started (PID: 1234)\n \n10-28 14:00:00 [NapCat内置适配器] 连接失败", text.text)
        val thirdLineStart = text.text.lastIndexOf('\n') + 1
        val colored = text.spanStyles.map { Triple(it.start - thirdLineStart, it.end - thirdLineStart, it.item.color) }
        assertEquals(
            listOf(
                Triple(0, 14, Color(0xFFE5E510)),
                Triple(15, 28, Color(0xFF00AF87))
            ),
            colored
        )
    }
}
