package org.maiwithu.maidroid.webui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiRenderCrashGuardTest {
    @Test
    fun `reopens three times in two minutes then waits for the user`() {
        val guard = WebUiRenderCrashGuard()

        assertTrue(guard.shouldAutoReopen(now = 0))
        assertTrue(guard.shouldAutoReopen(now = 10_000))
        assertTrue(guard.shouldAutoReopen(now = 20_000))
        // 网页一加载就崩：第四次不再自动重开，免得死循环
        assertFalse(guard.shouldAutoReopen(now = 30_000))
    }

    @Test
    fun `crashes older than two minutes no longer count`() {
        val guard = WebUiRenderCrashGuard()
        repeat(3) { guard.shouldAutoReopen(now = it * 1_000L) }

        // 偶尔被系统回收一次（内存紧张）不算反复崩溃
        assertTrue(guard.shouldAutoReopen(now = 2 * 60_000L + 5_000))
    }
}
