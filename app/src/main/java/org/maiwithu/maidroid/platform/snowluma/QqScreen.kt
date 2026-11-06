package org.maiwithu.maidroid.platform.snowluma

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * QQ 窗口的一帧画面：Xvfb 屏幕上有内容的那块区域（屏幕像素坐标）和它的像素（ARGB_8888，逐行）。
 * signature 是这块像素的摘要，和上一帧一样就不用重画。
 */
internal class QqScreenFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val signature: Int
) {
    /** 用户点在画面上的 (fractionX, fractionY)（0~1，相对显示出来的这块区域）换成屏幕坐标，往 QQ 窗口里点击用。 */
    fun screenPoint(fractionX: Float, fractionY: Float): Pair<Int, Int> {
        val x = left + (fractionX.coerceIn(0f, 1f) * (width - 1)).toInt()
        val y = top + (fractionY.coerceIn(0f, 1f) * (height - 1)).toInt()
        return x to y
    }
}

/**
 * 解析 Xvfb -fbdir 写出的屏幕文件（XWD 格式），裁出 QQ 窗口那块。
 *
 * 文件布局（Xvfb 的 vfbWriteXWDFileHeader）：25 个 32 位大端字段的文件头 + 窗口名，共 header_size 字节 ->
 * ncolors 个 12 字节的调色板项 -> 逐行的像素，每行 bytes_per_line 字节，像素字节序看 byte_order（arm64 上是低位在前）。
 * 屏幕是 24 位色，每个像素占 32 位，按 red/green/blue_mask 取颜色。
 * 没有窗口管理器，屏幕上除了 QQ 的窗口都是背景色，所以按“和左上角那个像素不同”框出窗口所在的区域。
 */
internal object XwdScreen {
    private const val HEADER_FIELDS = 25
    private const val XWD_FILE_VERSION = 7
    private const val Z_PIXMAP = 2
    private const val LSB_FIRST = 0
    private const val XWD_COLOR_SIZE = 12

    /** 解析一帧；文件还没写好、格式不认识，或者屏幕上什么窗口都没有（QQ 还没画出来）时返回 null。 */
    fun decode(bytes: ByteArray, length: Int = bytes.size): QqScreenFrame? {
        if (length < HEADER_FIELDS * 4) return null
        val header = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        val headerSize = header.getInt(0)
        if (header.getInt(4) != XWD_FILE_VERSION || header.getInt(8) != Z_PIXMAP) return null
        val width = header.getInt(16)
        val height = header.getInt(20)
        val byteOrder = header.getInt(28)
        val bitsPerPixel = header.getInt(44)
        val bytesPerLine = header.getInt(48)
        val redMask = header.getInt(56)
        val greenMask = header.getInt(60)
        val blueMask = header.getInt(64)
        val colorCount = header.getInt(76)
        if (bitsPerPixel != 32 || width <= 0 || height <= 0 || bytesPerLine < width * 4) return null
        if (redMask == 0 || greenMask == 0 || blueMask == 0) return null
        val dataOffset = headerSize.toLong() + colorCount.toLong() * XWD_COLOR_SIZE
        if (headerSize < HEADER_FIELDS * 4 || colorCount < 0) return null
        if (dataOffset + bytesPerLine.toLong() * height > length) return null

        val pixels = ByteBuffer.wrap(bytes, 0, length)
            .order(if (byteOrder == LSB_FIRST) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val colorBits = redMask or greenMask or blueMask
        val base = dataOffset.toInt()
        fun raw(x: Int, y: Int): Int = pixels.getInt(base + y * bytesPerLine + x * 4) and colorBits

        // 背景色取左上角；框出所有和它不同的像素
        val background = raw(0, 0)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (raw(x, y) != background) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null

        val cropWidth = maxX - minX + 1
        val cropHeight = maxY - minY + 1
        val red = Channel(redMask)
        val green = Channel(greenMask)
        val blue = Channel(blueMask)
        val argb = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            for (x in 0 until cropWidth) {
                val value = raw(minX + x, minY + y)
                argb[y * cropWidth + x] =
                    (0xFF shl 24) or (red.of(value) shl 16) or (green.of(value) shl 8) or blue.of(value)
            }
        }
        val signature = 31 * (31 * argb.contentHashCode() + minX * 4099 + minY) + cropWidth * 8191 + cropHeight
        return QqScreenFrame(minX, minY, cropWidth, cropHeight, argb, signature)
    }

    // 按掩码取出一个颜色分量，换算到 0~255
    private class Channel(private val mask: Int) {
        private val shift = Integer.numberOfTrailingZeros(mask)
        private val max = (mask ushr shift)

        fun of(value: Int): Int {
            val component = (value and mask) ushr shift
            return if (max == 0xFF) component else component * 255 / max
        }
    }
}

/**
 * 反复读同一个屏幕文件：复用读缓冲区（文件约 4 MB，弹窗开着时每秒读一次），读不到或格式不对返回 null。
 * 不是线程安全的，只在一个协程里用。
 */
internal class QqScreenReader(private val file: File) {
    private var buffer = ByteArray(0)

    fun read(): QqScreenFrame? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length <= 0 || length > Int.MAX_VALUE) return@use null
            if (buffer.size < length) buffer = ByteArray(length.toInt())
            input.readFully(buffer, 0, length.toInt())
            XwdScreen.decode(buffer, length.toInt())
        }
    }.getOrNull()
}
