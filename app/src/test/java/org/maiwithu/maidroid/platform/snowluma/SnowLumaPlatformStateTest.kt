package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnowLumaPlatformStateTest {
    private val installed = SnowLumaPlatformState.initial(installed = true, active = true)

    @Test
    fun `logged in when the onebot port is open or snowluma says so`() {
        assertFalse(installed.loggedIn)
        assertTrue(installed.copy(oneBotReachable = true).loggedIn)
        assertTrue(installed.copy(hookPhase = SnowLumaHookPhase.LoggedIn).loggedIn)
        assertFalse(installed.copy(hookPhase = SnowLumaHookPhase.WaitingLogin).loggedIn)
    }

    @Test
    fun `login hint tells the user what to do in the qq window`() {
        assertTrue(snowLumaLoginHint(installed.copy(running = true, hookPhase = SnowLumaHookPhase.WaitingLogin)).contains("扫码"))
        assertEquals("QQ 正在启动…", snowLumaLoginHint(installed.copy(running = true, hookPhase = SnowLumaHookPhase.QqStarting)))
        // 注入失败把 SnowLuma 的原因带上，没给原因时指到终端
        assertTrue(
            snowLumaLoginHint(installed.copy(hookPhase = SnowLumaHookPhase.Failed, hookError = "[COMPONENT_LOAD_FAILED]"))
                .contains("COMPONENT_LOAD_FAILED")
        )
        assertTrue(snowLumaLoginHint(installed.copy(hookPhase = SnowLumaHookPhase.Failed)).contains("[SnowLuma]"))
        // 管理页接口不通时按进程在不在跑说
        assertEquals("SnowLuma 正在启动…", snowLumaLoginHint(installed.copy(running = true)))
        assertTrue(snowLumaLoginHint(installed).contains("重启 QQ"))
    }
}
