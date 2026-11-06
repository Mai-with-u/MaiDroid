package org.maiwithu.maidroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.runtime.ProcessExitOutcome

class SafeExitStateTest {

    private fun state(maiBot: SafeExitStage, qq: SafeExitStage, ssh: SafeExitStage) = SafeExitState(
        started = true,
        stages = mapOf(
            SafeExitService.MaiBot to maiBot,
            SafeExitService.Qq to qq,
            SafeExitService.ContainerSsh to ssh
        )
    )

    @Test
    fun `not finished before the user confirms or while any service is still stopping`() {
        assertFalse(SafeExitState().finished)
        assertFalse(state(SafeExitStage.Exited, SafeExitStage.Stopping, SafeExitStage.NotRunning).finished)
        assertFalse(state(SafeExitStage.WaitingForUpdate, SafeExitStage.Exited, SafeExitStage.Exited).finished)
    }

    @Test
    fun `finished once every service exited, was force stopped or was not running`() {
        assertTrue(state(SafeExitStage.Exited, SafeExitStage.ForceStopped, SafeExitStage.NotRunning).finished)
    }

    @Test
    fun `notification shows the first service that is still stopping`() {
        assertEquals(
            "MaiBot：正在保存数据并退出",
            safeExitNotificationText(state(SafeExitStage.Stopping, SafeExitStage.Stopping, SafeExitStage.Stopping))
        )
        assertEquals(
            "MaiBot：正在等在线更新完成，之后再退出",
            safeExitNotificationText(state(SafeExitStage.WaitingForUpdate, SafeExitStage.Exited, SafeExitStage.Exited))
        )
        assertEquals(
            "QQ：正在下线退出",
            safeExitNotificationText(state(SafeExitStage.Exited, SafeExitStage.Stopping, SafeExitStage.Exited))
        )
    }

    @Test
    fun `napcat and snowluma are merged into one qq row`() {
        // 只有一个协议端在跑：另一个的“未在运行”不能盖过真实结果
        assertEquals(ProcessExitOutcome.Exited, mergeExitOutcomes(ProcessExitOutcome.NotRunning, ProcessExitOutcome.Exited))
        assertEquals(ProcessExitOutcome.ForceStopped, mergeExitOutcomes(ProcessExitOutcome.ForceStopped, ProcessExitOutcome.NotRunning))
        assertEquals(ProcessExitOutcome.NotRunning, mergeExitOutcomes(ProcessExitOutcome.NotRunning, ProcessExitOutcome.NotRunning))
    }

    @Test
    fun `notification says closing once every service stopped`() {
        assertEquals(
            "服务都已停下，正在关闭 MaiDroid",
            safeExitNotificationText(state(SafeExitStage.Exited, SafeExitStage.NotRunning, SafeExitStage.ForceStopped))
        )
    }
}
