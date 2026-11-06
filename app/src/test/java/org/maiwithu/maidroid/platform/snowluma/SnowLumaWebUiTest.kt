package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnowLumaWebUiTest {
    private fun process(status: String, injected: Boolean = false, loggedIn: Boolean = false, pid: Int = 100) =
        SnowLumaQqProcess(pid, status, injected, loggedIn, uin = "", nickname = "", error = "")

    @Test
    fun `process list is parsed with the account nested under it`() {
        val body = """
            {"list":[
              {"pid":4321,"name":"qq","injected":true,"connected":true,"loggedIn":true,"uin":"123456",
               "status":"online","error":"","method":"ptrace","account":{"uin":"123456","nickname":"麦麦"}},
              {"pid":"oops"},
              {"pid":4400,"injected":false,"loggedIn":false,"uin":"0","status":"error",
               "error":"[COMPONENT_LOAD_FAILED] load failed","account":null}
            ]}
        """.trimIndent()

        val processes = parseSnowLumaProcesses(body)

        assertEquals(2, processes.size)
        assertEquals(SnowLumaQqProcess(4321, "online", true, true, "123456", "麦麦", ""), processes[0])
        // uin 为 0 表示还不知道是哪个号
        assertEquals("", processes[1].uin)
        assertTrue(processes[1].error.contains("COMPONENT_LOAD_FAILED"))
        assertTrue(parseSnowLumaProcesses("not json").isEmpty())
    }

    @Test
    fun `login phase follows the snowluma process status`() {
        assertEquals(SnowLumaHookPhase.QqStarting, snowLumaHookPhase(emptyList()))
        assertEquals(SnowLumaHookPhase.Injecting, snowLumaHookPhase(listOf(process("available"))))
        assertEquals(SnowLumaHookPhase.Injecting, snowLumaHookPhase(listOf(process("connecting", injected = true))))
        assertEquals(SnowLumaHookPhase.WaitingLogin, snowLumaHookPhase(listOf(process("loaded", injected = true))))
        assertEquals(SnowLumaHookPhase.LoggedIn, snowLumaHookPhase(listOf(process("online", injected = true, loggedIn = true))))
        assertEquals(SnowLumaHookPhase.Failed, snowLumaHookPhase(listOf(process("error"))))
        assertEquals(SnowLumaHookPhase.Disconnected, snowLumaHookPhase(listOf(process("disconnected", injected = true))))
    }

    @Test
    fun `a logged in qq wins over a second one still waiting`() {
        val waiting = process("loaded", injected = true, pid = 1)
        val online = process("online", injected = true, loggedIn = true, pid = 2)

        assertEquals(online, primaryQqProcess(listOf(waiting, online)))
        assertEquals(SnowLumaHookPhase.LoggedIn, snowLumaHookPhase(listOf(waiting, online)))
    }
}
