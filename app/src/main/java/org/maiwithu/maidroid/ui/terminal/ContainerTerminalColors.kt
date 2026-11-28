package org.maiwithu.maidroid.ui.terminal

import androidx.compose.ui.graphics.toArgb
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.LightMaiDroidColors

/** 交互终端底部快捷按键栏提供的常用控制键与导航符号。 */
internal enum class TerminalExtraKey(val label: String, val stickyModifier: Boolean = false) {
    Escape("ESC"),
    Tab("TAB"),
    Ctrl("CTRL", stickyModifier = true),
    Alt("ALT", stickyModifier = true),
    CtrlC("^C"),
    Up("↑"),
    Down("↓"),
    Left("←"),
    Right("→"),
    Dash("-"),
    Slash("/"),
    Pipe("|"),
    Tilde("~"),
    Home("HOME"),
    End("END"),
    PageUp("PGUP"),
    PageDown("PGDN")
}

// 暗底高辨识 ANSI 16 色（标准 0..7 + 高亮 8..15）
private val DARK_ANSI_16 = intArrayOf(
    0xFF1A1C22.toInt(),
    0xFFFF5F56.toInt(),
    0xFF27C93F.toInt(),
    0xFFFFBD2E.toInt(),
    0xFF5FAFFF.toInt(),
    0xFFD787FF.toInt(),
    0xFF39C5BB.toInt(),
    0xFFD0D0D4.toInt(),
    0xFF6B6F78.toInt(),
    0xFFFF7B72.toInt(),
    0xFF56D364.toInt(),
    0xFFE3B341.toInt(),
    0xFF79C0FF.toInt(),
    0xFFE8A2FF.toInt(),
    0xFF56D4DD.toInt(),
    0xFFFFFFFF.toInt()
)

// 浅底高对比压暗 ANSI 16 色，确保浅色终端下 ls / git / vim 色彩与文本都清晰易读
private val LIGHT_ANSI_16 = intArrayOf(
    0xFF1F2329.toInt(),
    0xFFC92A2A.toInt(),
    0xFF1E7E34.toInt(),
    0xFF9C5700.toInt(),
    0xFF1864AB.toInt(),
    0xFF862E9C.toInt(),
    0xFF0B7285.toInt(),
    0xFF495057.toInt(),
    0xFF5C6270.toInt(),
    0xFFB02525.toInt(),
    0xFF166534.toInt(),
    0xFF8A4B00.toInt(),
    0xFF145394.toInt(),
    0xFF702483.toInt(),
    0xFF095C6B.toInt(),
    0xFF16181D.toInt()
)

/** 生成符合 Termux 259 色槽规范（ANSI 16 色 + 216 色立方 + 24 级灰度 + 前景/背景/光标）的调色板数组。 */
internal fun buildTerminalPalette(isDark: Boolean): IntArray {
    val palette = IntArray(TextStyle.NUM_INDEXED_COLORS)
    val baseDefaults = TerminalColors.COLOR_SCHEME.mDefaultColors
    baseDefaults.copyInto(palette, endIndex = minOf(baseDefaults.size, palette.size))

    val ansi16 = if (isDark) DARK_ANSI_16 else LIGHT_ANSI_16
    ansi16.copyInto(palette, destinationOffset = 0)

    val themePalette = if (isDark) DarkMaiDroidColors else LightMaiDroidColors
    palette[TextStyle.COLOR_INDEX_FOREGROUND] = themePalette.terminalText.toArgb()
    palette[TextStyle.COLOR_INDEX_BACKGROUND] = themePalette.terminalBackground.toArgb()
    palette[TextStyle.COLOR_INDEX_CURSOR] = BrandOrange.toArgb()
    return palette
}

/** 把当前深浅主题调色板同步到 Termux 默认配色槽和当前已初始化会话的模拟器色表中。 */
internal fun applyTerminalThemeColors(session: TerminalSession?, isDark: Boolean) {
    val palette = buildTerminalPalette(isDark)
    palette.copyInto(TerminalColors.COLOR_SCHEME.mDefaultColors)
    session?.emulator?.mColors?.let { colors ->
        palette.copyInto(colors.mCurrentColors)
    }
}

/**
 * 将非粘滞快捷按键翻译成要写入 PTY 的字节序列字符串；
 * 粘滞键（CTRL / ALT）不直接发序列，由调用方切换修饰状态；应用光标模式（DECCKM）决定方向键与 Home/End 前缀。
 */
internal fun resolveExtraKeySequence(
    key: TerminalExtraKey,
    applicationCursorMode: Boolean
): String? {
    val cursorPrefix = if (applicationCursorMode) "\u001BO" else "\u001B["
    return when (key) {
        TerminalExtraKey.Escape -> "\u001B"
        TerminalExtraKey.Tab -> "\t"
        TerminalExtraKey.Ctrl,
        TerminalExtraKey.Alt -> null
        TerminalExtraKey.CtrlC -> "\u0003"
        TerminalExtraKey.Up -> "${cursorPrefix}A"
        TerminalExtraKey.Down -> "${cursorPrefix}B"
        TerminalExtraKey.Right -> "${cursorPrefix}C"
        TerminalExtraKey.Left -> "${cursorPrefix}D"
        TerminalExtraKey.Dash -> "-"
        TerminalExtraKey.Slash -> "/"
        TerminalExtraKey.Pipe -> "|"
        TerminalExtraKey.Tilde -> "~"
        TerminalExtraKey.Home -> "${cursorPrefix}H"
        TerminalExtraKey.End -> "${cursorPrefix}F"
        TerminalExtraKey.PageUp -> "\u001B[5~"
        TerminalExtraKey.PageDown -> "\u001B[6~"
    }
}
