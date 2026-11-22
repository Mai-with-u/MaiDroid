package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHttpTest {

    @Test
    fun `isLoopbackHost matches local loopback variants and ignores blank or remote hosts`() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("LOCALHOST"))
        assertTrue(isLoopbackHost("::1"))
        assertTrue(isLoopbackHost("[::1]"))
        assertFalse(isLoopbackHost("192.168.1.1"))
        assertFalse(isLoopbackHost("ops.8u.nz"))
        assertFalse(isLoopbackHost(""))
        assertFalse(isLoopbackHost(null))
    }
}
