package org.maiwithu.maidroid.oobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.FailureCause

class OobeAutoRetryTest {
    @Test
    fun `offline failures wait for the network every time`() {
        // 断网不限次数：每次都要先等到网，不会空转
        repeat(5) { retries ->
            val plan = oobeAutoRetry(FailureCause.NoNetwork, retries)
            assertTrue(plan is OobeAutoRetry.AfterReconnect)
            assertEquals("连上网后会自动重试", plan!!.note)
        }
    }

    @Test
    fun `other network failures retry twice after a delay`() {
        assertEquals(OobeAutoRetry.AfterDelay(30_000), oobeAutoRetry(FailureCause.Timeout, 0))
        assertEquals("90 秒后自动重试", oobeAutoRetry(FailureCause.Dns, 1)!!.note)
        assertNull(oobeAutoRetry(FailureCause.ConnectionLost, 2))
    }

    @Test
    fun `failures a retry cannot fix are left to the user`() {
        listOf(FailureCause.DiskFull, FailureCause.ClockSkew, FailureCause.DependencyConflict, null).forEach { cause ->
            assertNull(oobeAutoRetry(cause, 0))
        }
    }
}
