package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Test

class MaiBotAgreementTest {

    @Test
    fun `hash matches md5 of the lf text`() {
        // printf 'line1\nline2\n' | md5sum
        assertEquals("4fcc82a88ee38e0aa16c17f512c685c9", agreementHash("line1\nline2\n"))
    }

    @Test
    fun `crlf and lone cr hash the same as lf like python text mode`() {
        val lf = agreementHash("line1\nline2\n")

        assertEquals(lf, agreementHash("line1\r\nline2\r\n"))
        assertEquals(lf, agreementHash("line1\rline2\r"))
    }
}
