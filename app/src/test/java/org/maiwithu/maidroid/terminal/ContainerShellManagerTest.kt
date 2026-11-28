package org.maiwithu.maidroid.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerShellManagerTest {

    @Test
    fun rejectsZeroAndNegativeSessionPidsToPreventProcessGroupSigkill() {
        assertFalse(canTerminateTerminalSessionPid(0))
        assertFalse(canTerminateTerminalSessionPid(-1))
        assertFalse(canTerminateTerminalSessionPid(-42))
    }

    @Test
    fun allowsPositiveSessionPids() {
        assertTrue(canTerminateTerminalSessionPid(1))
        assertTrue(canTerminateTerminalSessionPid(12345))
    }
}
