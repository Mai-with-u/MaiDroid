package org.maiwithu.maidroid.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NapCatWebUiPageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun clientWith(config: String) =
        NapCatWebUiClient(folder.newFile("webui.json").apply { writeText(config) })

    @Test
    fun `page url keeps trailing slash and carries the token for auto login`() {
        val client = clientWith("""{"host":"::","port":6100,"token":"a b&c"}""")
        assertEquals("http://127.0.0.1:6100/webui/?token=a+b%26c", client.pageUrl())
    }

    @Test
    fun `page url falls back to login page without token`() {
        val client = clientWith("""{"host":"::"}""")
        assertEquals("http://127.0.0.1:6099/webui/", client.pageUrl())
    }

    @Test
    fun `qr login completes once napcat is ready or onebot is reachable`() {
        val waiting = NapCatPlatformState.initial(installed = true, qrCodePath = null, qrCodeLastModified = 0L)
            .copy(loginPhase = "qrcode_scanned")
        assertFalse(waiting.qrLoginCompleted)
        assertTrue(waiting.copy(loginPhase = "ready").qrLoginCompleted)
        assertTrue(waiting.copy(oneBotReachable = true).qrLoginCompleted)
    }
}
