package org.maiwithu.maidroid.appupdate

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.random.Random

class AppUpdateClientTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val content = Random(7).nextBytes(2 * 1024 * 1024 + 123)
    private val rangeHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: HttpServer

    // 第几次请求要在发出这么多字节后断开；不在表里的完整发完
    private val cutAfterBytes = mutableMapOf<Int, Int>()
    private var supportsRange = true

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/a.apk") { exchange -> serveApk(exchange) }
        server.createContext("/latest") { exchange ->
            val body = """{"tag_name":"v0.1.3","assets":[{"name":"MaiDroid_v0.1.3.apk","size":${content.size},
                "browser_download_url":"${url("/a.apk")}"}],"body":"- 新功能"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/broken") { exchange ->
            val body = "<html>403</html>".toByteArray()
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun serveApk(exchange: HttpExchange) {
        val index = rangeHeaders.size
        val range = exchange.requestHeaders.getFirst("Range")
        rangeHeaders.add(range)
        val start = if (supportsRange) range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0 else 0
        val length = content.size - start
        exchange.responseHeaders.add("Content-Type", "application/vnd.android.package-archive")
        if (supportsRange && range != null) {
            exchange.responseHeaders.add("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
            exchange.sendResponseHeaders(206, length.toLong())
        } else {
            exchange.sendResponseHeaders(200, content.size.toLong())
        }
        val cut = cutAfterBytes[index]
        runCatching {
            exchange.responseBody.write(content, start, minOf(length, cut ?: length))
        }
        exchange.close()
    }

    private val asset get() = AppReleaseAsset("MaiDroid_v0.1.3.apk", content.size.toLong(), url("/a.apk"))

    private val listener = object : ApkDownloadListener {
        val failures = mutableListOf<String>()
        override fun onSourceChosen(source: String, startByte: Long) = Unit
        override fun onProgress(downloadedBytes: Long, totalBytes: Long, source: String) = Unit
        override fun onSourceFailed(source: String, reason: String) {
            failures += source
        }
    }

    @Test
    fun `fetches the release from a non github source directly`() = runBlocking {
        val client = AppUpdateClient("MaiDroid/test")

        val win = client.fetchLatestRelease(url("/latest"))

        assertEquals("v0.1.3", win?.value?.tag)
        assertEquals(url("/a.apk"), win?.value?.apk?.downloadUrl)
        assertNull(client.fetchLatestRelease(url("/broken")))
    }

    @Test
    fun `resumes from the existing part file`() = runBlocking {
        val part = temp.newFile("MaiDroid-0.1.3.apk.part")
        part.writeBytes(content.copyOfRange(0, 1000))

        AppUpdateClient("MaiDroid/test").downloadApk(asset, part, listener)

        assertEquals("bytes=1000-", rangeHeaders.single())
        assertArrayEquals(content, part.readBytes())
    }

    @Test
    fun `reconnects after a dropped connection and continues where it stopped`() = runBlocking {
        cutAfterBytes[0] = 700 * 1024
        val part = temp.newFile("MaiDroid-0.1.3.apk.part")

        AppUpdateClient("MaiDroid/test").downloadApk(asset, part, listener)

        assertEquals(2, rangeHeaders.size)
        assertEquals("bytes=0-", rangeHeaders[0])
        assertTrue(rangeHeaders[1]!!.removePrefix("bytes=").substringBefore('-').toLong() > 0)
        assertEquals(1, listener.failures.size)
        assertArrayEquals(content, part.readBytes())
    }

    @Test
    fun `server without range support restarts the file from zero`() = runBlocking {
        supportsRange = false
        val part = temp.newFile("MaiDroid-0.1.3.apk.part")
        part.writeBytes(ByteArray(5000) { 1 })

        AppUpdateClient("MaiDroid/test").downloadApk(asset, part, listener)

        assertArrayEquals(content, part.readBytes())
    }

    @Test
    fun `speed meter averages over the recent window`() {
        val meter = DownloadSpeedMeter(windowNanos = 3_000_000_000L)

        assertEquals(0L, meter.sample(0L, 0L))
        assertEquals(1_000_000L, meter.sample(1_000_000_000L, 1_000_000L))
        assertEquals(1_000_000L, meter.sample(2_000_000_000L, 2_000_000L))
        meter.reset()
        assertEquals(0L, meter.sample(3_000_000_000L, 2_000_000L))
    }

    @Test
    fun `sizes are shown in binary units`() {
        assertEquals("155.6 MB", formatBytes(163_110_537L))
        assertEquals("512 KB", formatBytes(512 * 1024L))
        assertEquals("900 B", formatBytes(900))
    }
}
