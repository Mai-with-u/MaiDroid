package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebUiDownloadNamesTest {
    @Test
    fun usesContentDispositionForDataTransferExport() {
        // MaiBot 数据迁移的下载接口：地址最后一段是 download，名字只在 Content-Disposition 里
        val name = downloadFileName(
            suggestedName = null,
            url = "http://127.0.0.1:8001/api/webui/data-transfer/export/21454e3e/download",
            contentDisposition = "attachment; filename=\"maibot-data-20261031-202931.zip\"",
            mimeType = "application/zip"
        )

        assertEquals("maibot-data-20261031-202931.zip", name)
    }

    @Test
    fun prefersDownloadAttributeOverHeaderAndUrl() {
        val name = downloadFileName(
            suggestedName = "maibot-theme-1.json",
            url = "http://127.0.0.1:8001/files/other.txt",
            contentDisposition = "attachment; filename=\"header.zip\"",
            mimeType = "application/json"
        )

        assertEquals("maibot-theme-1.json", name)
    }

    @Test
    fun decodesExtendedFileNameBeforePlainOne() {
        // Starlette 遇到中文名只写 filename*，两者都有时以 filename* 为准
        assertEquals(
            "麦麦 备份.zip",
            contentDispositionFileName("attachment; filename*=utf-8''%E9%BA%A6%E9%BA%A6%20%E5%A4%87%E4%BB%BD.zip")
        )
        assertEquals(
            "新.zip",
            contentDispositionFileName("attachment; filename=\"old.zip\"; filename*=UTF-8''%E6%96%B0.zip")
        )
    }

    @Test
    fun parsesQuotedAndBareFileNames() {
        assertEquals("a \"b\".txt", contentDispositionFileName("attachment; filename=\"a \\\"b\\\".txt\""))
        assertEquals("plain.txt", contentDispositionFileName("attachment; filename=plain.txt"))
        assertNull(contentDispositionFileName("attachment"))
        // 加号不是空格
        assertEquals("a+b.zip", contentDispositionFileName("attachment; filename*=utf-8''a+b.zip"))
    }

    @Test
    fun fallsBackToUrlPathOnlyWhenItHasExtension() {
        // 路径里的百分号编码要解开，查询参数不算进名字
        assertEquals("app%log.txt", downloadFileName(null, "https://example.com/dl/app%25log.txt?x=1", null, null))
        assertEquals("download.zip", downloadFileName(null, "http://127.0.0.1/export/download", null, "application/zip"))
    }

    @Test
    fun blobUrlWithoutNameGuessesExtensionFromType() {
        // blob 地址的路径是 uuid，没有名字时按类型补扩展名，类型带参数也认
        assertEquals(
            "download.txt",
            downloadFileName(null, "blob:http://127.0.0.1:8001/6f1c-uuid", null, "text/plain;charset=utf-8")
        )
        assertEquals("download", downloadFileName("", "blob:http://127.0.0.1:8001/6f1c", null, "application/x-unknown"))
    }

    @Test
    fun sanitizesPathsAndIllegalCharacters() {
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("a_b_c.json", sanitizeFileName("a:b*c.json"))
        assertEquals("hidden.txt", sanitizeFileName("  .hidden.txt. "))
        assertNull(sanitizeFileName(" ... "))
        assertNull(sanitizeFileName("???"))
    }

    @Test
    fun truncatesLongNamesKeepingExtension() {
        val name = sanitizeFileName("x".repeat(300) + ".json")!!

        assertEquals(120, name.length)
        assertEquals(true, name.endsWith(".json"))
    }

    @Test
    fun saveMimeTypeMatchesExtensionSoSystemKeepsName() {
        // 类型和扩展名对不上时系统会再补一个扩展名，所以认识的扩展名按扩展名定类型
        assertEquals("application/zip", saveMimeType("maibot-data.zip", "application/x-zip-compressed"))
        assertEquals("application/json", saveMimeType("theme.json", ""))
        // 不认识的扩展名用 octet-stream，名字原样保留
        assertEquals("application/octet-stream", saveMimeType("bot_config.toml", "application/toml"))
        // 没有扩展名时用网页给的类型，交给系统补扩展名
        assertEquals("text/plain", saveMimeType("logs", "Text/Plain; charset=utf-8"))
        assertEquals("application/octet-stream", saveMimeType("logs", "not a type"))
    }
}
