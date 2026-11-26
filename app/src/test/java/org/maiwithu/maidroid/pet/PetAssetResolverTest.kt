package org.maiwithu.maidroid.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PetAssetResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `prefers local container web assets when index exists and falls back per missing file`() {
        val containerDir = tempFolder.newFolder("container_web")
        File(containerDir, "index.html").writeText(
            "<!doctype html><html><head><title>local</title></head><body>local</body></html>",
            Charsets.UTF_8
        )
        File(containerDir, "skin.js").writeText("console.log('local-skin');", Charsets.UTF_8)

        val fallbackZipBytes = buildZipBytes(
            "index.html" to "<!doctype html><html><head><title>zip</title></head><body>zip</body></html>".toByteArray(),
            "sounds/wooden-pop.wav" to byteArrayOf(1, 2, 3, 4)
        )

        val resolver = PetAssetResolver(
            containerWebDir = containerDir,
            openFallbackZip = { ByteArrayInputStream(fallbackZipBytes) },
            openAndroidAsset = { null }
        )

        assertTrue(resolver.hasLocalContainerDesktopPet())

        val htmlAsset = resolver.resolve("/pet/index.html")
        assertNotNull(htmlAsset)
        assertEquals(PetAssetSource.LocalContainer, htmlAsset?.source)
        assertEquals("text/html", htmlAsset?.mimeType)
        assertTrue(htmlAsset?.injectCsp == true)
        val htmlText = String(htmlAsset!!.bytes, Charsets.UTF_8)
        assertTrue(htmlText.contains("<title>local</title>"))

        val soundAsset = resolver.resolve("/pet/sounds/wooden-pop.wav")
        assertNotNull(soundAsset)
        assertEquals(PetAssetSource.BundledFallback, soundAsset?.source)
        assertEquals("audio/wav", soundAsset?.mimeType)
        assertFalse(soundAsset?.injectCsp ?: true)
    }

    @Test
    fun `falls back to zip when local container has no desktop_pet directory`() {
        val missingDir = File(tempFolder.root, "missing_web")
        val fallbackZipBytes = buildZipBytes(
            "index.html" to "<!doctype html><html><head><script src=\"host-bridge.js\"></script></head><body>zip</body></html>".toByteArray(),
            "fonts/ArkPixel12-ZhHans.woff2" to byteArrayOf(9, 8, 7)
        )

        val resolver = PetAssetResolver(
            containerWebDir = missingDir,
            openFallbackZip = { ByteArrayInputStream(fallbackZipBytes) },
            openAndroidAsset = { null }
        )

        assertFalse(resolver.hasLocalContainerDesktopPet())
        val htmlAsset = resolver.resolve("/pet/index.html")
        assertNotNull(htmlAsset)
        assertEquals(PetAssetSource.BundledFallback, htmlAsset?.source)

        val fontAsset = resolver.resolve("/pet/fonts/ArkPixel12-ZhHans.woff2")
        assertNotNull(fontAsset)
        assertEquals("font/woff2", fontAsset?.mimeType)
        assertFalse(fontAsset?.injectCsp ?: true)
    }

    @Test
    fun `injects polyfill before desktop bridge scripts and preserves existing viewport`() {
        val resolver = PetAssetResolver(
            containerWebDir = File(tempFolder.root, "unused"),
            openFallbackZip = { null },
            openAndroidAsset = { null }
        )

        val petHtml = resolver.injectHtmlAssets(
            "<html><head><script src=\"host-bridge.js\"></script></head><body></body></html>"
        )
        val polyfillIdx = petHtml.indexOf("/android/polyfill.js")
        val channelIdx = petHtml.indexOf("/android/channel.js")
        val bridgeIdx = petHtml.indexOf("/android/bridge.js")
        val hostBridgeIdx = petHtml.indexOf("host-bridge.js")
        val mobileCssIdx = petHtml.indexOf("/android/mobile.css")
        val headCloseIdx = petHtml.indexOf("</head>")

        assertTrue(polyfillIdx > 0)
        assertTrue(polyfillIdx < channelIdx)
        assertTrue(channelIdx < bridgeIdx)
        assertTrue(bridgeIdx < hostBridgeIdx)
        assertTrue(petHtml.contains("name=\"viewport\""))
        assertTrue(mobileCssIdx in (hostBridgeIdx + 1) until headCloseIdx)

        val recordsHtml = resolver.injectHtmlAssets(
            "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body></body></html>"
        )
        assertEquals(1, Regex("""name=["']viewport["']""").findAll(recordsHtml).count())
    }

    @Test
    fun `blocks path traversal and unsupported paths`() {
        val androidScripts = mapOf("polyfill.js" to "window.__ok = true;".toByteArray())
        val resolver = PetAssetResolver(
            containerWebDir = File(tempFolder.root, "unused"),
            openFallbackZip = { null },
            openAndroidAsset = { name -> androidScripts[name]?.let(::ByteArrayInputStream) }
        )

        assertNull(resolver.resolve("/pet/../secrets.txt"))
        assertNull(resolver.resolve("/android/../etc/passwd"))
        assertNull(resolver.resolve("/etc/passwd"))
        assertNull(resolver.resolve("/pet/"))
        assertNull(resolver.resolve("/pet/index.html?foo=1"))

        val bridge = resolver.resolve("/android/polyfill.js")
        assertNotNull(bridge)
        assertEquals(PetAssetSource.AndroidBridge, bridge?.source)
        assertEquals("application/javascript", bridge?.mimeType)
    }

    @Test
    fun `parses http byte ranges for audio and rejects invalid ranges`() {
        assertEquals(PetRangeResult.NoRange, parseHttpByteRange(null, 100))
        assertEquals(PetRangeResult.NoRange, parseHttpByteRange("   ", 100))

        assertEquals(
            PetRangeResult.Partial(PetByteRange(0, 99)),
            parseHttpByteRange("bytes=0-", 100)
        )
        assertEquals(
            PetRangeResult.Partial(PetByteRange(10, 25)),
            parseHttpByteRange("BYTES=10-25", 100)
        )
        assertEquals(16, (parseHttpByteRange("bytes=10-25", 100) as PetRangeResult.Partial).range.length)
        assertEquals(
            PetRangeResult.Partial(PetByteRange(80, 99)),
            parseHttpByteRange("bytes=80-999", 100)
        )
        assertEquals(
            PetRangeResult.Partial(PetByteRange(80, 99)),
            parseHttpByteRange("bytes=-20", 100)
        )
        assertEquals(
            PetRangeResult.Partial(PetByteRange(0, 99)),
            parseHttpByteRange("bytes=-500", 100)
        )
        assertEquals(
            PetRangeResult.Partial(PetByteRange(0, 9)),
            parseHttpByteRange("bytes=0-9, 20-29", 100)
        )

        assertEquals(PetRangeResult.Unsatisfiable, parseHttpByteRange("bytes=100-", 100))
        assertEquals(PetRangeResult.Unsatisfiable, parseHttpByteRange("bytes=20-10", 100))
        assertEquals(PetRangeResult.Unsatisfiable, parseHttpByteRange("bytes=-0", 100))
        assertEquals(PetRangeResult.Unsatisfiable, parseHttpByteRange("items=0-5", 100))
        assertEquals(PetRangeResult.Unsatisfiable, parseHttpByteRange("bytes=0-10", 0))
    }

    private fun buildZipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
