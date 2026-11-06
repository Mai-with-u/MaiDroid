package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XwdScreenTest {
    // 按 Xvfb 的写法造一个屏幕文件：大端文件头 + 64 字节窗口名 + 调色板 + 低位在前的 32 位像素
    private fun xwd(width: Int, height: Int, colors: Int = 256, paint: (x: Int, y: Int) -> Int): ByteArray {
        val headerSize = 100 + 64
        val bytesPerLine = width * 4
        val dataOffset = headerSize + colors * 12
        val buffer = ByteBuffer.allocate(dataOffset + bytesPerLine * height)
        buffer.order(ByteOrder.BIG_ENDIAN)
        val fields = intArrayOf(
            headerSize, 7, 2, 24, width, height, 0, 0, 32, 0, 32, 32, bytesPerLine, 4,
            0xFF0000, 0x00FF00, 0x0000FF, 8, colors, colors, width, height, 0, 0, 0
        )
        fields.forEachIndexed { index, value -> buffer.putInt(index * 4, value) }
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 高 8 位是没用的填充，随便填点东西，解析时要忽略
                buffer.putInt(dataOffset + y * bytesPerLine + x * 4, paint(x, y) or 0x7F000000)
            }
        }
        return buffer.array()
    }

    @Test
    fun `crops to the qq window on a plain background`() {
        // 8x6 的黑屏，(2,1) 到 (4,3) 是“窗口”：白底，中间一个红点
        val bytes = xwd(8, 6) { x, y ->
            when {
                x == 3 && y == 2 -> 0xFF0000
                x in 2..4 && y in 1..3 -> 0xFFFFFF
                else -> 0x000000
            }
        }

        val frame = XwdScreen.decode(bytes)!!

        assertEquals(2, frame.left)
        assertEquals(1, frame.top)
        assertEquals(3, frame.width)
        assertEquals(3, frame.height)
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        assertArrayEquals(intArrayOf(white, white, white, white, red, white, white, white, white), frame.pixels)
        // 点在画面正中，落到屏幕上窗口的正中
        assertEquals(3 to 2, frame.screenPoint(0.5f, 0.5f))
        assertEquals(2 to 1, frame.screenPoint(0f, 0f))
        assertEquals(4 to 3, frame.screenPoint(1f, 1f))
    }

    @Test
    fun `nothing on screen yet means no frame`() {
        assertNull(XwdScreen.decode(xwd(4, 4) { _, _ -> 0x101010 }))
    }

    @Test
    fun `truncated or foreign files are rejected`() {
        val bytes = xwd(4, 4) { x, _ -> if (x == 1) 0xFFFFFF else 0 }
        assertNull(XwdScreen.decode(bytes, length = bytes.size - 1))
        assertNull(XwdScreen.decode(ByteArray(40)))
        val png = bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 0x89504E47.toInt()) }
        assertNull(XwdScreen.decode(png))
    }

    @Test
    fun `signature changes when the window content changes`() {
        val before = XwdScreen.decode(xwd(6, 6) { x, y -> if (x in 1..4 && y in 1..4) 0xFFFFFF else 0 })!!
        val same = XwdScreen.decode(xwd(6, 6) { x, y -> if (x in 1..4 && y in 1..4) 0xFFFFFF else 0 })!!
        val after = XwdScreen.decode(xwd(6, 6) { x, y -> if (x == 2 && y == 2) 0x00FF00 else if (x in 1..4 && y in 1..4) 0xFFFFFF else 0 })!!

        assertEquals(before.signature, same.signature)
        assertNotEquals(before.signature, after.signature)
    }
}
