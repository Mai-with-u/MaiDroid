package org.maiwithu.maidroid.webui

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.Charset

/*
 * 网页下载弹“另存为”时预填的文件名和交给系统的 MIME 类型，由 WebUiDownloads 调用。
 * 不依赖 Android，可以直接单测。
 */

private const val FALLBACK_BASE_NAME = "download"
private const val OCTET_STREAM = "application/octet-stream"
private const val MAX_FILE_NAME_LENGTH = 120

// 扩展名和类型的对照，只收 Android 自己的 MimeTypeMap 认法一致的几种：
// 文件名后缀和类型对不上时，系统存储会在文件名后面再补一个扩展名（x.zip 变成 x.zip.json），所以宁缺毋滥。
private val mimeTypesByExtension = mapOf(
    "zip" to "application/zip",
    "json" to "application/json",
    "txt" to "text/plain",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp"
)

private val extensionsByMimeType = mapOf(
    "application/zip" to "zip",
    "application/json" to "json",
    "text/plain" to "txt",
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/gif" to "gif",
    "image/webp" to "webp"
)

private val mimeTypeRegex = Regex("""^[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+$""")

// Content-Disposition 里的 filename*=charset'语言'百分号编码（RFC 5987）和 filename="..."（或不带引号的 token）。
private val extendedFileNameRegex = Regex("""(?:^|;)\s*filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
private val plainFileNameRegex = Regex("""(?:^|;)\s*filename\s*=\s*("(?:[^"\\]|\\.)*"|[^;]*)""", RegexOption.IGNORE_CASE)

// 文件名里不能出现的字符：路径分隔符、FAT/exFAT（U 盘、SD 卡）不认的符号和控制字符。
private val unsafeFileNameChars = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")

/**
 * 预填的文件名，按可信程度依次取：
 * 网页 download 属性给的名字 -> 响应头 Content-Disposition -> http 地址路径的最后一段（要带扩展名才算）-> download.<按类型猜的扩展名>。
 * MaiBot 数据迁移的下载地址是 .../export/<任务号>/download，最后一段没有扩展名，名字靠 Content-Disposition；
 * 主题、设置等 blob 地址的路径是一串 uuid，名字靠 download 属性。
 */
internal fun downloadFileName(
    suggestedName: String?,
    url: String,
    contentDisposition: String?,
    mimeType: String?
): String {
    val candidates = sequenceOf(
        suggestedName,
        contentDisposition?.let(::contentDispositionFileName),
        urlPathFileName(url)
    )
    candidates.mapNotNull { it?.let(::sanitizeFileName) }.firstOrNull()?.let { return it }

    val extension = normalizedMimeType(mimeType)?.let(extensionsByMimeType::get)
    return if (extension == null) FALLBACK_BASE_NAME else "$FALLBACK_BASE_NAME.$extension"
}

/**
 * 从 Content-Disposition 里取文件名：有 filename*（可写中文）先用它，没有再用 filename。
 * MaiBot 的下载接口（Starlette 的 FileResponse）名字是纯 ASCII 时写 filename="..."，带中文时只写 filename*=utf-8''...。
 */
internal fun contentDispositionFileName(header: String): String? {
    extendedFileNameRegex.find(header)?.groupValues?.get(1)?.trim()?.let { value ->
        decodeExtendedValue(value)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    val raw = plainFileNameRegex.find(header)?.groupValues?.get(1)?.trim() ?: return null
    val name = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) {
        raw.substring(1, raw.length - 1).replace(Regex("""\\(.)"""), "$1")
    } else {
        raw
    }
    return name.takeIf { it.isNotBlank() }
}

/**
 * 交给“另存为”的 MIME 类型。文件名带的扩展名认识就按扩展名定，保证两者一致，系统不会再补扩展名；
 * 文件名没有扩展名时用网页给的类型（系统会按它补上合适的扩展名）；其余情况一律用 application/octet-stream，文件名原样保留。
 */
internal fun saveMimeType(fileName: String, mimeType: String?): String {
    val extension = fileName.substringAfterLast('.', "").lowercase()
    if (extension.isEmpty()) return normalizedMimeType(mimeType) ?: OCTET_STREAM
    return mimeTypesByExtension[extension] ?: OCTET_STREAM
}

/**
 * 去掉文件名里的路径和非法字符，首尾的空格和点也去掉（开头是点会变成隐藏文件），过长时保留扩展名截断。
 * 什么都不剩时返回 null，交给下一种来源。
 */
internal fun sanitizeFileName(name: String): String? {
    val baseName = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = unsafeFileNameChars.replace(baseName, "_").trim().trim('.', ' ')
    if (cleaned.isEmpty() || cleaned.all { it == '_' }) return null
    if (cleaned.length <= MAX_FILE_NAME_LENGTH) return cleaned

    val extension = cleaned.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
    return if (extension == null) {
        cleaned.take(MAX_FILE_NAME_LENGTH)
    } else {
        cleaned.take(MAX_FILE_NAME_LENGTH - extension.length - 1).trimEnd('.', ' ') + "." + extension
    }
}

// 类型去掉 ;charset=... 这类参数并转小写，不像 MIME 写法的返回 null。
private fun normalizedMimeType(mimeType: String?): String? =
    mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { mimeTypeRegex.matches(it) }

// 只看 http(s) 地址路径的最后一段，而且要带扩展名，否则像 .../download 这样的接口名会被当成文件名。
private fun urlPathFileName(url: String): String? {
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rawPath = runCatching { URI(url).rawPath }.getOrNull() ?: return null
    val lastSegment = rawPath.substringAfterLast('/')
    if (!lastSegment.contains('.')) return null
    return percentDecode(lastSegment, Charsets.UTF_8)
}

// filename* 的值形如 utf-8'zh-cn'%E5%AF%BC%E5%87%BA.zip：字符集 ' 语言 ' 百分号编码。
private fun decodeExtendedValue(value: String): String? {
    val parts = value.trim('"').split('\'', limit = 3)
    if (parts.size != 3) return null
    val charset = runCatching { Charset.forName(parts[0].trim()) }.getOrDefault(Charsets.UTF_8)
    return percentDecode(parts[2], charset)
}

// 只解百分号编码，加号保持原样（URLDecoder 会把加号当成空格，RFC 5987 和地址路径里都不是这个意思）。
private fun percentDecode(value: String, charset: Charset): String? {
    val bytes = ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%') {
            if (index + 2 >= value.length) return null
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
            bytes.write(byte)
            index += 3
        } else {
            bytes.write(char.toString().toByteArray(charset))
            index += 1
        }
    }
    return bytes.toString(charset.name())
}
