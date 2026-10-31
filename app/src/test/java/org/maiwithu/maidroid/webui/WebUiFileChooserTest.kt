package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Test

class WebUiFileChooserTest {
    @Test
    fun keepsMimeTypesFromAcceptAttribute() {
        // MaiBot 本地聊天室的图片按钮是 accept="image/*"
        assertEquals(listOf("image/*"), fileChooserMimeTypes(listOf("image/*")))
    }

    @Test
    fun splitsCommaSeparatedEntryAndNormalizesCase() {
        // WebView 有时把整串 accept 放在同一项里
        assertEquals(
            listOf("image/png", "video/*"),
            fileChooserMimeTypes(listOf(" Image/PNG , video/*"))
        )
    }

    @Test
    fun dropsExtensionsAndDuplicates() {
        // 选文件页不认 .png 这类扩展名写法，只留 MIME 类型
        assertEquals(
            listOf("image/png"),
            fileChooserMimeTypes(listOf(".png", "image/png", "image/png", ""))
        )
    }

    @Test
    fun returnsEmptyWhenNothingUsable() {
        // 没写 accept 或只写了扩展名时返回空表，调用方退回“任意文件”
        assertEquals(emptyList<String>(), fileChooserMimeTypes(emptyList()))
        assertEquals(emptyList<String>(), fileChooserMimeTypes(listOf(".txt", "image/")))
    }
}
