package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
