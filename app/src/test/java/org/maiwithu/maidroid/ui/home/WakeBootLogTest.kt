package org.maiwithu.maidroid.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState

class WakeBootLogTest {
    private val starting = MaiBotProcessStatus()

    private fun updateLine(phase: MaiBotUpdatePhase) = maiBotUpdateBootLine(MaiBotUpdateState(phase = phase))

    @Test
    fun `normal boot has three stages`() {
        val lines = bootLines(webUiOnline = false, status = starting, exits = 0)

        assertEquals(listOf("container runtime", "maibot process", "webui endpoint"), lines.map { it.label })
    }

    @Test
    fun `auto update line sits between container and maibot process`() {
        val lines = bootLines(false, starting, 0, update = updateLine(MaiBotUpdatePhase.Updating))

        assertEquals(
            listOf("container runtime", "maibot update", "maibot process", "webui endpoint"),
            lines.map { it.label }
        )
        assertEquals(BootCheck.Pending, lines[1].check)
    }

    @Test
    fun `update line concludes on every finished outcome`() {
        assertEquals(BootCheck.Done, updateLine(MaiBotUpdatePhase.Updated).check)
        assertEquals(BootCheck.Done, updateLine(MaiBotUpdatePhase.UpToDate).check)
        // 拉取失败、升级失败都不挡后面，麦麦照常用现在的版本启动
        assertEquals("skipped", updateLine(MaiBotUpdatePhase.Idle).status)
        assertTrue(updateLine(MaiBotUpdatePhase.Idle).check.concluded)
        assertEquals("failed", updateLine(MaiBotUpdatePhase.Failed).status)
        assertTrue(updateLine(MaiBotUpdatePhase.Failed).check.concluded)
    }

    @Test
    fun `progress targets of a normal boot are unchanged`() {
        val waiting = bootLines(false, starting, 0)
        val running = bootLines(false, starting.copy(running = true), 0)
        val online = bootLines(true, starting.copy(running = true), 0)

        assertEquals(0.14f, bootProgressTarget(1, waiting, 0f)!!, 1e-6f)
        assertEquals(0.36f, bootProgressTarget(2, running, 0f)!!, 1e-6f)
        assertEquals(0.97f, bootProgressTarget(3, online, 0f)!!, 1e-6f)
        assertEquals(1f, bootProgressTarget(4, online, 0f)!!, 1e-6f)
    }

    @Test
    fun `auto update takes its own stretch before the maibot process`() {
        val updating = bootLines(false, starting, 0, update = updateLine(MaiBotUpdatePhase.Updating))
        val updated = bootLines(false, starting, 0, update = updateLine(MaiBotUpdatePhase.Updated))
        val running = bootLines(false, starting.copy(running = true), 0, update = updateLine(MaiBotUpdatePhase.Updated))

        // 升级中进度慢慢逼近本段上限，不会越过
        val creeping = bootProgressTarget(2, updating, waitingSeconds = 600f)!!
        assertTrue(creeping > 0.14f && creeping <= 0.28f)
        assertEquals(0.30f, bootProgressTarget(2, updated, 0f)!!, 1e-6f)
        assertEquals(0.52f, bootProgressTarget(3, running, 0f)!!, 1e-6f)
    }

    @Test
    fun `blocked container stops the progress bar`() {
        val blocked = bootLines(false, starting.copy(launchBlockedReason = "容器未就绪"), 0)

        assertNull(bootProgressTarget(1, blocked, 0f))
    }

    @Test
    fun wakePageShowsCodeOnlyWhenMaiBotCannotStart() {
        var outputRead = false
        val output = {
            outputRead = true
            "Traceback (most recent call last):\nModuleNotFoundError: No module named 'aiohttp'"
        }
        // 只退出过一次还在正常重启：不报错，也不去读输出
        assertNull(wakeFailure(MaiBotProcessStatus(exitCount = 1), exits = 1, recentOutput = output))
        assertEquals(false, outputRead)

        assertEquals(
            "MB-CRASH/PY-MODULE",
            wakeFailure(MaiBotProcessStatus(exitCount = 3, lastExitCode = 1), exits = 2, recentOutput = output)?.code
        )
        assertEquals(
            "MB-BLOCKED",
            wakeFailure(MaiBotProcessStatus(launchBlockedReason = "proot 未就绪"), exits = 0, recentOutput = output)?.code
        )
    }
}
