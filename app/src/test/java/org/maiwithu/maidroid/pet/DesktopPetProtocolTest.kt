package org.maiwithu.maidroid.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPetProtocolTest {

    @Test
    fun `normalizeOrigin strips default ports and rejects invalid or credential urls`() {
        assertEquals("http://127.0.0.1:8001", DesktopPetProtocol.normalizeOrigin("http://127.0.0.1:8001/auth?token=abc"))
        assertEquals("http://127.0.0.1", DesktopPetProtocol.normalizeOrigin("http://127.0.0.1:80/"))
        assertEquals("https://example.com", DesktopPetProtocol.normalizeOrigin("https://EXAMPLE.COM:443/webui"))
        assertEquals("https://example.com:8443", DesktopPetProtocol.normalizeOrigin("https://example.com:8443"))
        assertEquals("", DesktopPetProtocol.normalizeOrigin("http://user:pass@127.0.0.1:8001/"))
        assertEquals("", DesktopPetProtocol.normalizeOrigin("not-a-url"))
    }

    @Test
    fun `isTrustedServerUrl enforces same origin and path prefix`() {
        val local = "http://127.0.0.1:8001"
        assertTrue(DesktopPetProtocol.isTrustedServerUrl(local, "http://127.0.0.1:8001/auth?token=123"))
        assertTrue(DesktopPetProtocol.isTrustedServerUrl(local, "http://127.0.0.1:8001/api/host/desktop-pet/session"))
        assertFalse(DesktopPetProtocol.isTrustedServerUrl(local, "http://127.0.0.1:8002/auth"))
        assertFalse(DesktopPetProtocol.isTrustedServerUrl(local, "https://127.0.0.1:8001/auth"))
        assertFalse(DesktopPetProtocol.isTrustedServerUrl(local, "https://evil.example/"))

        val prefixed = "https://host.example/maibot"
        assertTrue(DesktopPetProtocol.isTrustedServerUrl(prefixed, "https://host.example/maibot/auth"))
        assertFalse(DesktopPetProtocol.isTrustedServerUrl(prefixed, "https://host.example/other/auth"))
    }

    @Test
    fun `isSessionDocumentUrl detects light transport page accurately`() {
        val local = "http://127.0.0.1:8001"
        assertTrue(DesktopPetProtocol.isSessionDocumentUrl(local, "http://127.0.0.1:8001/api/host/desktop-pet/session"))
        assertFalse(DesktopPetProtocol.isSessionDocumentUrl(local, "http://127.0.0.1:8001/auth"))
        assertFalse(DesktopPetProtocol.isSessionDocumentUrl(local, "http://127.0.0.1:8001/"))
    }

    @Test
    fun `validates bot ids, client ids, external urls and remote activation rule`() {
        assertTrue(DesktopPetProtocol.isValidBotId("0123456789abcdef0123456789abcdef"))
        assertFalse(DesktopPetProtocol.isValidBotId("0123456789abcdef"))
        assertFalse(DesktopPetProtocol.isValidBotId("0123456789ABCDEF0123456789ABCDEF"))

        assertTrue(DesktopPetProtocol.isValidClientId("maidroid_pet-01"))
        assertFalse(DesktopPetProtocol.isValidClientId(""))
        assertFalse(DesktopPetProtocol.isValidClientId("bad id!"))

        assertTrue(DesktopPetProtocol.isAllowedExternalHttpUrl("https://github.com/Mai-with-u"))
        assertTrue(DesktopPetProtocol.isAllowedExternalHttpUrl("http://127.0.0.1:8001/"))
        assertFalse(DesktopPetProtocol.isAllowedExternalHttpUrl("javascript:alert(1)"))
        assertFalse(DesktopPetProtocol.isAllowedExternalHttpUrl("file:///etc/passwd"))

        assertTrue(DesktopPetRemoteConfigState(enabled = false, mode = "local").needsRemoteActivation)
        assertTrue(DesktopPetRemoteConfigState(enabled = true, mode = "local").needsRemoteActivation)
        assertTrue(DesktopPetRemoteConfigState(enabled = false, mode = "remote").needsRemoteActivation)
        assertFalse(DesktopPetRemoteConfigState(enabled = true, mode = "remote").needsRemoteActivation)
    }

    @Test
    fun `computeDragStartPayload calculates finite anchor offsets and clamps scale`() {
        val normal = DesktopPetProtocol.computeDragStartPayload(
            gestureId = "g1",
            windowX = 300,
            windowWidth = 780,
            windowBottom = 1800,
            pointerDownX = 640.4f,
            pointerDownY = 1650.2f,
            density = 3.0f
        )
        assertEquals("g1", normal.gestureId)
        assertEquals(50, normal.offsetX)
        assertEquals(150, normal.offsetY)
        assertEquals(3.0, normal.scale, 0.0001)

        val nonFinite = DesktopPetProtocol.computeDragStartPayload(
            gestureId = "g2",
            windowX = 100,
            windowWidth = 520,
            windowBottom = 1000,
            pointerDownX = Float.NaN,
            pointerDownY = Float.POSITIVE_INFINITY,
            density = Float.NaN
        )
        assertEquals(0, nonFinite.offsetX)
        assertEquals(0, nonFinite.offsetY)
        assertEquals(1.0, nonFinite.scale, 0.0001)

        val invalidScaleLow = DesktopPetProtocol.computeDragStartPayload(
            gestureId = "g3",
            windowX = 100,
            windowWidth = 520,
            windowBottom = 1000,
            pointerDownX = 360f,
            pointerDownY = 900f,
            density = -2.0f
        )
        assertEquals(1.0, invalidScaleLow.scale, 0.0001)

        val clampedHigh = DesktopPetProtocol.computeDragStartPayload(
            gestureId = "g4",
            windowX = 100,
            windowWidth = 520,
            windowBottom = 1000,
            pointerDownX = 360f,
            pointerDownY = 900f,
            density = 32.0f
        )
        assertEquals(DesktopPetProtocol.MAX_DRAG_SCALE, clampedHigh.scale, 0.0001)
    }
}
