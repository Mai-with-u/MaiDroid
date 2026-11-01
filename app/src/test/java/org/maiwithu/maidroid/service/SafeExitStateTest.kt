package org.maiwithu.maidroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeExitStateTest {

    private fun state(maiBot: SafeExitStage, napCat: SafeExitStage, ssh: SafeExitStage) = SafeExitState(
        started = true,
        stages = mapOf(
            SafeExitService.MaiBot to maiBot,
            SafeExitService.NapCat to napCat,
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
            "NapCat（QQ）：QQ 正在下线退出",
            safeExitNotificationText(state(SafeExitStage.Exited, SafeExitStage.Stopping, SafeExitStage.Exited))
        )
    }

    @Test
    fun `notification says closing once every service stopped`() {
        assertEquals(
            "服务都已停下，正在关闭 MaiDroid",
            safeExitNotificationText(state(SafeExitStage.Exited, SafeExitStage.NotRunning, SafeExitStage.ForceStopped))
        )
    }
}
