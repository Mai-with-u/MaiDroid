package org.maiwithu.maidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayRefreshRateTest {

    @Test
    fun `selects highest refresh rate matching current physical resolution`() {
        val selected = selectPreferredHighRefreshMode(
            currentWidth = 1256,
            currentHeight = 2808,
            modes = listOf(
                DisplayRefreshModeSpec(modeId = 1, physicalWidth = 1256, physicalHeight = 2808, refreshRate = 60f),
                DisplayRefreshModeSpec(modeId = 2, physicalWidth = 1256, physicalHeight = 2808, refreshRate = 120.00001f),
                DisplayRefreshModeSpec(modeId = 3, physicalWidth = 1256, physicalHeight = 2808, refreshRate = 90f),
                DisplayRefreshModeSpec(modeId = 4, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 144f)
            )
        )
        assertEquals(DisplayRefreshModeSelection(modeId = 2, refreshRate = 120.00001f), selected)
    }

    @Test
    fun `returns null on 60hz display or empty mode list`() {
        assertNull(
            selectPreferredHighRefreshMode(
                currentWidth = 1080,
                currentHeight = 2400,
                modes = listOf(
                    DisplayRefreshModeSpec(modeId = 1, physicalWidth = 1080, physicalHeight = 2400, refreshRate = 60f),
                    DisplayRefreshModeSpec(modeId = 2, physicalWidth = 720, physicalHeight = 1600, refreshRate = 120f)
                )
            )
        )
        assertNull(
            selectPreferredHighRefreshMode(
                currentWidth = 1080,
                currentHeight = 2400,
                modes = emptyList()
            )
        )
    }
}
