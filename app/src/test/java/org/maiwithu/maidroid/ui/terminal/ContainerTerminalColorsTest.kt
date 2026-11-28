package org.maiwithu.maidroid.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerTerminalColorsTest {
    @Test
    fun `dark and light terminal palettes fill all indexed slots with proper contrast`() {
        val dark = buildTerminalPalette(isDark = true)
        val light = buildTerminalPalette(isDark = false)

        assertEquals(TextStyle.NUM_INDEXED_COLORS, dark.size)
        assertEquals(TextStyle.NUM_INDEXED_COLORS, light.size)

        assertEquals(0xFF000000.toInt(), dark[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(0xFFF6F7FA.toInt(), light[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(0xFFE97F0F.toInt(), dark[TextStyle.COLOR_INDEX_CURSOR])
        assertEquals(0xFFE97F0F.toInt(), light[TextStyle.COLOR_INDEX_CURSOR])

        assertTrue(Color(dark[TextStyle.COLOR_INDEX_FOREGROUND]).luminance() > 0.5f)
        assertTrue(Color(light[TextStyle.COLOR_INDEX_FOREGROUND]).luminance() < 0.2f)
    }

    @Test
    fun `extra key mapping handles sticky modifiers and application cursor mode`() {
        assertNull(resolveExtraKeySequence(TerminalExtraKey.Ctrl, applicationCursorMode = false))
        assertNull(resolveExtraKeySequence(TerminalExtraKey.Alt, applicationCursorMode = false))

        assertEquals("\u001B", resolveExtraKeySequence(TerminalExtraKey.Escape, applicationCursorMode = false))
        assertEquals("\t", resolveExtraKeySequence(TerminalExtraKey.Tab, applicationCursorMode = false))
        assertEquals("\u0003", resolveExtraKeySequence(TerminalExtraKey.CtrlC, applicationCursorMode = false))
        assertEquals("\u001B[A", resolveExtraKeySequence(TerminalExtraKey.Up, applicationCursorMode = false))
        assertEquals("\u001BOA", resolveExtraKeySequence(TerminalExtraKey.Up, applicationCursorMode = true))
        assertEquals("\u001B[H", resolveExtraKeySequence(TerminalExtraKey.Home, applicationCursorMode = false))
        assertEquals("\u001BOF", resolveExtraKeySequence(TerminalExtraKey.End, applicationCursorMode = true))
        assertEquals("\u001B[5~", resolveExtraKeySequence(TerminalExtraKey.PageUp, applicationCursorMode = false))
        assertEquals("\u001B[6~", resolveExtraKeySequence(TerminalExtraKey.PageDown, applicationCursorMode = false))
    }
}
