package org.maiwithu.maidroid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNumberTest {
    @Test
    fun `versions compare numerically and stable beats prerelease`() {
        val v012 = VersionNumber.parse("0.1.2")!!
        assertTrue(VersionNumber.parse("v0.1.3")!! > v012)
        assertTrue(VersionNumber.parse("0.1.10")!! > VersionNumber.parse("0.1.9")!!)
        assertTrue(VersionNumber.parse("0.2")!! > v012)
        assertEquals(0, VersionNumber.parse("0.1.2.0")!!.compareTo(v012))
        assertTrue(VersionNumber.parse("0.2.0")!! > VersionNumber.parse("0.2.0-rc1")!!)
        assertTrue(VersionNumber.parse("0.2.0-rc2")!! > VersionNumber.parse("0.2.0-rc1")!!)
        assertTrue(VersionNumber.parse("0.2.0-rc.10")!! > VersionNumber.parse("0.2.0-rc.9")!!)
        assertTrue(VersionNumber.parse("0.2.0-rc1")!! > v012)
    }

    @Test
    fun `version parsing rejects junk and keeps the canonical text`() {
        assertNull(VersionNumber.parse("latest"))
        assertNull(VersionNumber.parse("v1..2"))
        assertNull(VersionNumber.parse("1.2.3-"))
        assertNull(VersionNumber.parse("1.2.3-../x"))
        assertEquals("0.1.0-beta", VersionNumber.parse("v0.1.0-beta").toString())
        assertEquals("1.0.0", VersionNumber.parse("1.0.0+build7").toString())
    }
}
