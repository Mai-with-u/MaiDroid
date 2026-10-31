package org.maiwithu.maidroid.webui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class WebUiBlobReceiverTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 临时目录不预先建好，顺带验证 begin 会自己建
    private fun receiver(maxBytes: Long = 1024) = WebUiBlobReceiver(temp.root.resolve("downloads"), maxBytes)

    private fun base64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun reassemblesChunksIntoFile() {
        // 脚本每块单独 base64（带填充），宿主逐块解开再拼接
        val receiver = receiver()
        val id = receiver.begin("theme.json", "application/json", 5)!!

        assertTrue(receiver.append(id, base64("ab".toByteArray())))
        assertTrue(receiver.append(id, base64("cde".toByteArray())))
        val received = receiver.finish(id)

        assertNotNull(received)
        assertArrayEquals("abcde".toByteArray(), received!!.file.readBytes())
        assertEquals("theme.json", received.fileName)
        assertEquals("application/json", received.mimeType)
    }

    @Test
    fun emptyFileIsAllowed() {
        val receiver = receiver()
        val id = receiver.begin("empty.txt", "text/plain", 0)!!

        val received = receiver.finish(id)

        assertEquals(0L, received!!.file.length())
    }

    @Test
    fun refusesFilesOverLimit() {
        assertNull(receiver(maxBytes = 4).begin("big.zip", "application/zip", 5))
        assertNull(receiver().begin("bad.zip", "application/zip", -1))
    }

    @Test
    fun dropsTransferWhenDataExceedsDeclaredSizeOrIsShort() {
        val receiver = receiver()
        val tooMuch = receiver.begin("a.txt", "text/plain", 2)!!
        assertFalse(receiver.append(tooMuch, base64("abc".toByteArray())))
        assertNull(receiver.finish(tooMuch))

        val tooLittle = receiver.begin("b.txt", "text/plain", 3)!!
        assertTrue(receiver.append(tooLittle, base64("ab".toByteArray())))
        assertNull(receiver.finish(tooLittle))
        assertEquals(0, temp.root.resolve("downloads").listFiles()!!.size)
    }

    @Test
    fun rejectsBrokenBase64AndUnknownIds() {
        val receiver = receiver()
        val id = receiver.begin("a.txt", "text/plain", 3)!!

        assertFalse(receiver.append("other", base64("abc".toByteArray())))
        assertFalse(receiver.append(id, "!!!not base64"))
        // 坏数据会丢掉整个文件，之后的块也不再收
        assertFalse(receiver.append(id, base64("abc".toByteArray())))
        assertNull(receiver.finish(id))
    }

    @Test
    fun newBeginDropsUnfinishedTransfer() {
        val receiver = receiver()
        val first = receiver.begin("first.txt", "text/plain", 3)!!
        receiver.append(first, base64("a".toByteArray()))

        val second = receiver.begin("second.txt", "text/plain", 1)!!

        assertFalse(receiver.append(first, base64("bc".toByteArray())))
        assertTrue(receiver.append(second, base64("z".toByteArray())))
        assertEquals("second.txt", receiver.finish(second)!!.fileName)
        assertEquals(1, temp.root.resolve("downloads").listFiles()!!.size)
    }

    @Test
    fun abortDeletesPartialFile() {
        val receiver = receiver()
        val id = receiver.begin("a.txt", "text/plain", 3)!!
        receiver.append(id, base64("a".toByteArray()))

        receiver.abort(id)

        assertEquals(0, temp.root.resolve("downloads").listFiles()!!.size)
        assertNull(receiver.finish(id))
    }

    @Test
    fun staleCleanupKeepsRecentFiles() {
        val directory = temp.newFolder("stale")
        val receiver = WebUiBlobReceiver(directory)
        val now = System.currentTimeMillis()
        val old = directory.resolve("old.part").apply { writeText("x"); setLastModified(now - 2 * 60 * 60 * 1000) }
        val recent = directory.resolve("recent.part").apply { writeText("y"); setLastModified(now - 60 * 1000) }

        receiver.deleteStaleFiles(now)

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
