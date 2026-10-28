package org.maiwithu.maidroid.oobe

import org.junit.Assert.assertEquals
import org.junit.Test

class OobeSetupStateTest {

    @Test
    fun `later step waits while an earlier step is not done`() {
        val tasks = sequentialTasks(
            OobeTaskCheck(OobeTaskKind.MaiBotSource, "MaiBot 源码", done = true),
            OobeTaskCheck(OobeTaskKind.PythonDependencies, "Python 依赖", done = false),
            OobeTaskCheck(OobeTaskKind.Agreement, "协议确认", done = true),
            OobeTaskCheck(OobeTaskKind.WebUi, "MaiBot WebUI", done = false)
        )

        assertEquals(
            listOf(OobeTaskStatus.Done, OobeTaskStatus.Waiting, OobeTaskStatus.Waiting, OobeTaskStatus.Waiting),
            tasks.map { it.status }
        )
    }

    @Test
    fun `all checks passing marks every step done`() {
        val tasks = sequentialTasks(
            OobeTaskCheck(OobeTaskKind.MaiBotSource, "MaiBot 源码", done = true),
            OobeTaskCheck(OobeTaskKind.PythonDependencies, "Python 依赖", done = true)
        )

        assertEquals(listOf(OobeTaskStatus.Done, OobeTaskStatus.Done), tasks.map { it.status })
        assertEquals(listOf("MaiBot 源码", "Python 依赖"), tasks.map { it.title })
    }
}
