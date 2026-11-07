package org.maiwithu.maidroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowBatteryPolicyTest {

    @Test
    fun `pauses only below five percent while unplugged`() {
        assertFalse(LowBatteryPolicy.shouldPause(paused = false, percent = 5, pluggedIn = false))
        assertTrue(LowBatteryPolicy.shouldPause(paused = false, percent = 4, pluggedIn = false))
        assertFalse(LowBatteryPolicy.shouldPause(paused = false, percent = 1, pluggedIn = true))
    }

    @Test
    fun `plugging in resumes right away`() {
        assertFalse(LowBatteryPolicy.shouldPause(paused = true, percent = 2, pluggedIn = true))
    }

    @Test
    fun `stays paused until ten percent so a bouncing reading does not restart maibot`() {
        // 电量读数在 4%、5% 之间来回跳：停下后到 9% 都还停着，到 10% 才恢复
        assertTrue(LowBatteryPolicy.shouldPause(paused = true, percent = 5, pluggedIn = false))
        assertTrue(LowBatteryPolicy.shouldPause(paused = true, percent = 9, pluggedIn = false))
        assertFalse(LowBatteryPolicy.shouldPause(paused = true, percent = 10, pluggedIn = false))
    }

    @Test
    fun `unknown level keeps the current state`() {
        assertTrue(LowBatteryPolicy.shouldPause(paused = true, percent = null, pluggedIn = false))
        assertFalse(LowBatteryPolicy.shouldPause(paused = false, percent = null, pluggedIn = false))
    }

    @Test
    fun `level and scale convert to percent`() {
        assertEquals(4, LowBatteryPolicy.percentOf(level = 4, scale = 100))
        assertEquals(50, LowBatteryPolicy.percentOf(level = 128, scale = 256))
        assertNull(LowBatteryPolicy.percentOf(level = -1, scale = 100))
        assertNull(LowBatteryPolicy.percentOf(level = 4, scale = 0))
    }
}
