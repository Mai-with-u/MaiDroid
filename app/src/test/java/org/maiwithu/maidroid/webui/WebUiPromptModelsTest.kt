package org.maiwithu.maidroid.webui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WebUiPromptModelsTest {

    @Test
    fun flattenSimpleSelectAndFindSelectedIndex() {
        val nodes = listOf(
            WebUiChoiceNode(id = "builtin", label = "麦麦（内置）"),
            WebUiChoiceNode(id = "orange-sprout", label = "橙芽与小鱼", selected = true),
            WebUiChoiceNode(id = "custom", label = "自定义")
        )
        val rows = flattenChoiceItems(nodes)

        assertEquals(3, rows.size)
        assertEquals(WebUiChoiceRow.Option("builtin", "麦麦（内置）", false, false, 0), rows[0])
        assertEquals(WebUiChoiceRow.Option("orange-sprout", "橙芽与小鱼", false, true, 0), rows[1])
        assertEquals(1, initialSelectedOptionIndex(rows))
        assertEquals(
            -1,
            initialSelectedOptionIndex(
                flattenChoiceItems(listOf(WebUiChoiceNode(id = "a", label = "A")))
            )
        )
    }

    @Test
    fun flattenOptgroupInheritsDisabledAndHandlesSeparators() {
        val nodes = listOf(
            WebUiChoiceNode(
                id = "group-enabled",
                label = "官方预设",
                items = listOf(
                    WebUiChoiceNode(id = "builtin", label = "内置麦麦"),
                    WebUiChoiceNode(id = "sep", label = "---", separator = true),
                    WebUiChoiceNode(id = "orange", label = "橙芽与小鱼", selected = true)
                )
            ),
            WebUiChoiceNode(
                id = "group-disabled",
                label = "已停用分组",
                disabled = true,
                items = listOf(
                    WebUiChoiceNode(id = "legacy", label = "旧版皮肤", disabled = false),
                    WebUiChoiceNode(
                        id = "nested",
                        label = "子分组",
                        items = listOf(
                            WebUiChoiceNode(id = "deep", label = "深层项")
                        )
                    )
                )
            )
        )

        val rows = flattenChoiceItems(nodes)
        assertEquals(
            listOf(
                WebUiChoiceRow.GroupHeader("官方预设", disabled = false, depth = 0),
                WebUiChoiceRow.Option("builtin", "内置麦麦", disabled = false, selected = false, depth = 1),
                WebUiChoiceRow.Separator,
                WebUiChoiceRow.Option("orange", "橙芽与小鱼", disabled = false, selected = true, depth = 1),
                WebUiChoiceRow.GroupHeader("已停用分组", disabled = true, depth = 0),
                WebUiChoiceRow.Option("legacy", "旧版皮肤", disabled = true, selected = false, depth = 1),
                WebUiChoiceRow.GroupHeader("子分组", disabled = true, depth = 1),
                WebUiChoiceRow.Option("deep", "深层项", disabled = true, selected = false, depth = 2)
            ),
            rows
        )
        assertEquals(3, initialSelectedOptionIndex(rows))
    }

    @Test
    fun multipleSelectionPreservesDomOrderAndDisabledPreselected() {
        val rows = flattenChoiceItems(
            listOf(
                WebUiChoiceNode(id = "first", label = "第一项", selected = true, disabled = true),
                WebUiChoiceNode(id = "second", label = "第二项", selected = false),
                WebUiChoiceNode(id = "third", label = "第三项", selected = true)
            )
        )

        val selected = initialSelectedChoiceIds(rows)
        assertEquals(linkedSetOf("first", "third"), selected)

        // 即使后续按反序插入集合，回传给 GeckoView 的 ID 数组仍按原始列表顺序排列。
        selected.remove("third")
        selected.add("second")
        selected.add("third")
        assertArrayEquals(arrayOf("first", "second", "third"), selectedChoiceIdsInOrder(rows, selected))

        // 清空多选时返回空数组而非 null，允许用户取消全部选项。
        selected.clear()
        assertTrue(selectedChoiceIdsInOrder(rows, selected).isEmpty())
    }

    @Test
    fun parseAndFormatDateValues() {
        val parsed = parseDateValue(" 2024-02-29 ")
        assertEquals(WebUiDateValue(2024, 2, 29), parsed)
        assertEquals("2024-02-29", formatDateValue(2024, 2, 29))

        assertNull(parseDateValue("2026-02-29"))
        assertNull(parseDateValue("2026-02-30"))
        assertNull(parseDateValue("2026-13-01"))
        assertNull(parseDateValue("2026-11-31"))
        assertNull(parseDateValue("not-a-date"))
    }

    @Test
    fun parseAndFormatTimeValues() {
        assertEquals(WebUiTimeValue(9, 5), parseTimeValue("09:05"))
        assertEquals(WebUiTimeValue(23, 59), parseTimeValue("23:59:42.750"))
        assertEquals("09:05", formatTimeValue(9, 5))

        assertNull(parseTimeValue("25:00"))
        assertNull(parseTimeValue("12:60"))
        assertNull(parseTimeValue("12:30:60"))
        assertNull(parseTimeValue("9:5"))
    }

    @Test
    fun parseAndFormatDateTimeLocalAndEpochMillis() {
        val dateTime = parseDateTimeLocalValue("2026-11-25T08:30:15")
        assertEquals(
            WebUiDateTimeValue(WebUiDateValue(2026, 11, 25), WebUiTimeValue(8, 30)),
            dateTime
        )
        assertEquals(
            WebUiDateTimeValue(WebUiDateValue(2026, 11, 25), WebUiTimeValue(8, 30)),
            parseDateTimeLocalValue("2026-11-25 08:30")
        )
        assertEquals(
            "2026-11-25T08:30",
            formatDateTimeLocalValue(WebUiDateValue(2026, 11, 25), WebUiTimeValue(8, 30))
        )
        assertNull(parseDateTimeLocalValue("2026-11-25"))

        val utc = TimeZone.getTimeZone("UTC")
        val expectedMillis = Calendar.getInstance(utc, Locale.ROOT).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 25, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(expectedMillis, parseDateEpochMillis("2026-11-25T18:45", utc))
        assertNull(parseDateEpochMillis("2026-02-30", utc))
    }

    @Test
    fun normalizeHexColorHandlesShortFullAndInvalidInputs() {
        assertEquals("#aabbcc", normalizeHexColor("#abc"))
        assertEquals("#aabbcc", normalizeHexColor("  AABBCC "))
        assertEquals("#ff00aa", normalizeHexColor("#Ff00aA"))

        assertNull(normalizeHexColor(null))
        assertNull(normalizeHexColor(""))
        assertNull(normalizeHexColor("#12345"))
        assertNull(normalizeHexColor("#11223344"))
        assertNull(normalizeHexColor("#zzzzzz"))
    }
}
