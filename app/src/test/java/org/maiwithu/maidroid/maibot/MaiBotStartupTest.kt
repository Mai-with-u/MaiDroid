package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotStartupTest {

    @Test
    fun `reachable webui counts only while maibot is running`() {
        val running = MaiBotProcessStatus(running = true)

        assertEquals(MaiBotStartupResult.Ready, maiBotStartupVerdict(running, 0, webUiReachable = true, elapsedMs = 5_000))
        assertNull(maiBotStartupVerdict(MaiBotProcessStatus(), 0, webUiReachable = true, elapsedMs = 5_000))
    }

    @Test
    fun `one exit keeps waiting and the second exit fails fast`() {
        val oneExit = MaiBotProcessStatus(exitCount = 4, lastExitCode = 1)
        val twoExits = MaiBotProcessStatus(exitCount = 5, lastExitCode = 1)

        assertNull(maiBotStartupVerdict(oneExit, baselineExitCount = 3, webUiReachable = false, elapsedMs = 20_000))
        assertEquals(
            MaiBotStartupResult.Crashed(exits = 2, lastExitCode = 1),
            maiBotStartupVerdict(twoExits, baselineExitCount = 3, webUiReachable = false, elapsedMs = 30_000)
        )
    }

    @Test
    fun `blocked launch is reported right away`() {
        val blocked = MaiBotProcessStatus(launchBlockedReason = "uv dependencies 未就绪：需要在 MaiBot 目录执行 uv sync")

        assertEquals(
            MaiBotStartupResult.Blocked("uv dependencies 未就绪：需要在 MaiBot 目录执行 uv sync"),
            maiBotStartupVerdict(blocked, 0, webUiReachable = false, elapsedMs = 1_000)
        )
    }

    @Test
    fun `port held by another program is reported right away`() {
        // 2026-11 真机装了两个 MaiDroid：8001 上是另一个的 WebUI，探测通了也不能当成自己的麦麦起来了
        val waitingForPort = MaiBotProcessStatus(webUiPortTaken = true)

        assertEquals(
            MaiBotStartupResult.PortTaken,
            maiBotStartupVerdict(waitingForPort, 0, webUiReachable = true, elapsedMs = 1_000)
        )
        assertEquals("MB-BLOCKED/PORT-BUSY", MaiBotStartupResult.PortTaken.failure("")?.code)
    }

    @Test
    fun `low battery pause is reported right away instead of waiting for the grace period`() {
        // 电量低暂停时监管器拦下了启动，进程一直不起来；首次引导不该等满一分钟再报“没拉起来”
        val paused = MaiBotProcessStatus(lowBatteryPaused = true)

        assertEquals(
            MaiBotStartupResult.LowBattery,
            maiBotStartupVerdict(paused, 0, webUiReachable = false, elapsedMs = 1_000)
        )
        assertEquals("MB-BLOCKED/BAT-LOW", MaiBotStartupResult.LowBattery.failure("")?.code)
    }

    @Test
    fun `process that never starts is reported after the grace period`() {
        val idle = MaiBotProcessStatus()

        assertNull(maiBotStartupVerdict(idle, 0, webUiReachable = false, elapsedMs = MaiBotStartup.NOT_STARTED_GRACE_MS - 1))
        assertEquals(
            MaiBotStartupResult.NotStarted,
            maiBotStartupVerdict(idle, 0, webUiReachable = false, elapsedMs = MaiBotStartup.NOT_STARTED_GRACE_MS)
        )
    }

    @Test
    fun `running process gets the long timeout before giving up`() {
        val running = MaiBotProcessStatus(running = true)

        assertNull(maiBotStartupVerdict(running, 0, webUiReachable = false, elapsedMs = 5 * 60_000))
        assertEquals(
            MaiBotStartupResult.TimedOut,
            maiBotStartupVerdict(running, 0, webUiReachable = false, elapsedMs = MaiBotStartup.TIMEOUT_MS)
        )
    }

    @Test
    fun `crash message names the exit count and code`() {
        assertEquals(
            "MaiBot 启动后连续退出了 2 次（最后退出码 1），请查看终端输出里的报错",
            MaiBotStartupResult.Crashed(exits = 2, lastExitCode = 1).failureMessage()
        )
        assertNull(MaiBotStartupResult.Ready.failureMessage())
    }


    @Test
    fun `startup failures carry codes and runtime causes only`() {
        val crashOutput = "[LLM] 请求超时：operation timed out\nModuleNotFoundError: No module named 'aiohttp'"
        assertEquals(
            "MB-CRASH/PY-MODULE",
            MaiBotStartupResult.Crashed(exits = 2, lastExitCode = 1).failure(crashOutput)?.code
        )
        // 运行中的网络超时不当作起不来的原因
        assertEquals("MB-TIMEOUT", MaiBotStartupResult.TimedOut.failure("operation timed out")?.code)
        assertEquals("MB-BLOCKED", MaiBotStartupResult.Blocked("容器未就绪").failure("")?.code)
        assertEquals("MB-NOSTART", MaiBotStartupResult.NotStarted.failure("")?.code)
        assertNull(MaiBotStartupResult.Ready.failure(crashOutput))
    }

    @Test
    fun `recent run output starts at the second last process start`() {
        val started = MaiBotProcessSupervisor.PROCESS_STARTED_LOG
        val lines = listOf("old", "$started (PID: 1)", "run1", "$started (PID: 2)", "run2", "$started (PID: 3)")
        assertEquals("$started (PID: 2)\nrun2\n$started (PID: 3)", maiBotRecentRunOutput(lines))
        assertEquals("a\nb", maiBotRecentRunOutput(listOf("a", "b")))
        assertEquals("$started (PID: 1)\nonly", maiBotRecentRunOutput(listOf("$started (PID: 1)", "only")))
    }

    @Test
    fun `stable run resets consecutive exit count and restart backoff`() {
        val initial = MaiBotProcessSupervisor.INITIAL_RESTART_DELAY_MS
        val maxDelay = MaiBotProcessSupervisor.MAX_RESTART_DELAY_MS

        // 短期退出：计数加一，本轮用当前间隔等待，下轮退避翻倍
        val short1 = nextMaiBotRestartState(uptimeMs = 4_000L, currentConsecutiveExits = 0, currentRestartDelayMs = initial)
        assertEquals(1, short1.consecutiveExits)
        assertEquals(initial, short1.waitDelayMs)
        assertEquals(initial * 2, short1.nextDelayMs)
        assertFalse(short1.resetFromStableRun)

        val short2 = nextMaiBotRestartState(uptimeMs = 15_000L, currentConsecutiveExits = short1.consecutiveExits, currentRestartDelayMs = maxDelay)
        assertEquals(2, short2.consecutiveExits)
        assertEquals(maxDelay, short2.waitDelayMs)
        assertEquals(maxDelay, short2.nextDelayMs)
        assertFalse(short2.resetFromStableRun)

        // 稳定运行满 10 分钟：即使前面累过计数、退避到上限，也立刻归零并按 5s 快速拉起
        val stable = nextMaiBotRestartState(
            uptimeMs = MaiBotProcessSupervisor.STABLE_RUNTIME_MS,
            currentConsecutiveExits = 2,
            currentRestartDelayMs = maxDelay
        )
        assertEquals(0, stable.consecutiveExits)
        assertEquals(initial, stable.waitDelayMs)
        assertEquals(initial, stable.nextDelayMs)
        assertTrue(stable.resetFromStableRun)
    }

    @Test
    fun `background crash report triggers at threshold and throttles until reset`() {
        assertFalse(shouldReportBackgroundMaiBotCrash(consecutiveExits = 1, lastReportedConsecutiveExits = 0))
        assertTrue(shouldReportBackgroundMaiBotCrash(consecutiveExits = 2, lastReportedConsecutiveExits = 0))
        assertFalse(shouldReportBackgroundMaiBotCrash(consecutiveExits = 3, lastReportedConsecutiveExits = 2))
        assertTrue(shouldReportBackgroundMaiBotCrash(consecutiveExits = 4, lastReportedConsecutiveExits = 2))
    }
}
