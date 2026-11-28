package org.maiwithu.maidroid.webui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class WebUiBridgeProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun configureJsonEncodesLandscapeAndPortraitModes() {
        val landscape = json.parseToJsonElement(
            WebUiBridgeProtocol.configureJsonText(desktopWidthCss = 1040, enableViewportPatch = false)
        ).jsonObject
        assertEquals("configure", landscape["type"]?.jsonPrimitive?.content)
        assertEquals(1040, landscape["desktopWidthCss"]?.jsonPrimitive?.intOrNull)
        assertEquals(false, landscape["enableViewportPatch"]?.jsonPrimitive?.booleanOrNull)

        val portrait = json.parseToJsonElement(
            WebUiBridgeProtocol.configureJsonText(desktopWidthCss = null, enableViewportPatch = true)
        ).jsonObject
        assertEquals("configure", portrait["type"]?.jsonPrimitive?.content)
        assertTrue(portrait["desktopWidthCss"] is JsonNull)
        assertEquals(true, portrait["enableViewportPatch"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun twoFingerMessagesKeepDotDecimalUnderCommaLocale() {
        // 德语等系统语言会把本地化浮点格式化为逗号；kotlinx.serialization 应始终输出标准 JSON 小数点
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val beginRaw = WebUiBridgeProtocol.twoFingerBeginJsonText(540.5f, 1200.25f, 1080)
            assertTrue(beginRaw.contains("540.5"))
            assertTrue(beginRaw.contains("1200.25"))
            assertFalse(beginRaw.contains(",5"))

            val dragRaw = WebUiBridgeProtocol.twoFingerMoveJsonText(-12.5f)
            assertTrue(dragRaw.contains("-12.5"))
            val dragObj = json.parseToJsonElement(dragRaw).jsonObject
            assertEquals("twoFingerMove", dragObj["type"]?.jsonPrimitive?.content)
            assertNull(dragObj["generation"])

            val flingRaw = WebUiBridgeProtocol.twoFingerMoveJsonText(3.25f, generation = 7)
            assertTrue(flingRaw.contains("3.25"))
            val flingObj = json.parseToJsonElement(flingRaw).jsonObject
            assertEquals(7, flingObj["generation"]?.jsonPrimitive?.intOrNull)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun blobRepliesReflectSuccessAndFailure() {
        val accepted = json.parseToJsonElement(WebUiBridgeProtocol.blobBeginReplyJsonText("tx-1")).jsonObject
        assertEquals("tx-1", accepted["id"]?.jsonPrimitive?.content)
        assertEquals(true, accepted["ok"]?.jsonPrimitive?.booleanOrNull)

        val rejected = json.parseToJsonElement(WebUiBridgeProtocol.blobBeginReplyJsonText(null)).jsonObject
        assertEquals("", rejected["id"]?.jsonPrimitive?.content)
        assertEquals(false, rejected["ok"]?.jsonPrimitive?.booleanOrNull)

        val okStatus = json.parseToJsonElement(WebUiBridgeProtocol.blobStatusReplyJsonText(true)).jsonObject
        assertEquals(true, okStatus["ok"]?.jsonPrimitive?.booleanOrNull)

        val failStatus = json.parseToJsonElement(WebUiBridgeProtocol.blobStatusReplyJsonText(false)).jsonObject
        assertEquals(false, failStatus["ok"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun parseEventHandlesAllBridgeEventsAndRejectsInvalidPayloads() {
        // “未来复古”深色主题实测底色 rgb(47, 44, 40)，以及半透明颜色
        assertEquals(
            WebUiBridgeEvent.PageBackground(0xFF2F2C28.toInt()),
            WebUiBridgeProtocol.parseEvent("""{"type":"pageBackground","r":47,"g":44,"b":40,"a":255}""")
        )
        assertEquals(
            WebUiBridgeEvent.PageBackground(0x80FFFFFF.toInt()),
            WebUiBridgeProtocol.parseEvent("""{"type":"pageBackground","r":255,"g":255,"b":255,"a":128}""")
        )
        assertNull(WebUiBridgeProtocol.parseEvent("""{"type":"pageBackground","r":47,"g":44,"b":300,"a":255}"""))
        assertNull(WebUiBridgeProtocol.parseEvent("""{"type":"pageBackground","r":47,"g":44,"b":40}"""))

        assertEquals(
            WebUiBridgeEvent.TwoFingerResult(canContinue = false, generation = 4),
            WebUiBridgeProtocol.parseEvent("""{"type":"twoFingerResult","canContinue":false,"generation":4}""")
        )

        assertEquals(
            WebUiBridgeEvent.BlobBegin("theme.json", "application/json", 128L),
            WebUiBridgeProtocol.parseEvent("""{"type":"blobBegin","fileName":"theme.json","mimeType":"application/json","sizeBytes":128}""")
        )
        assertEquals(
            WebUiBridgeEvent.BlobAppend("id-1", "YWJj"),
            WebUiBridgeProtocol.parseEvent("""{"type":"blobAppend","id":"id-1","base64":"YWJj"}""")
        )
        assertEquals(
            WebUiBridgeEvent.BlobFinish("id-1"),
            WebUiBridgeProtocol.parseEvent("""{"type":"blobFinish","id":"id-1"}""")
        )
        assertEquals(
            WebUiBridgeEvent.BlobFail("id-1", null),
            WebUiBridgeProtocol.parseEvent("""{"type":"blobFail","id":"id-1","message":null}""")
        )

        assertNull(WebUiBridgeProtocol.parseEvent("""{"type":"unknownEvent"}"""))
        assertNull(WebUiBridgeProtocol.parseEvent("""not-json"""))
    }

    @Test
    fun builtInExtensionManifestAndContentScriptMatchHostContracts() {
        val manifestText = extensionAsset("manifest.json").readText(Charsets.UTF_8)
        val manifest = json.parseToJsonElement(manifestText).jsonObject
        val geckoId = manifest["browser_specific_settings"]
            ?.jsonObject?.get("gecko")
            ?.jsonObject?.get("id")
            ?.jsonPrimitive?.content
        assertEquals(WebUiBridgeProtocol.EXTENSION_ID, geckoId)

        val contentScripts = manifest["content_scripts"]?.jsonArray.orEmpty()
        assertEquals(1, contentScripts.size)
        val entry = contentScripts[0].jsonObject
        assertEquals("document_start", entry["run_at"]?.jsonPrimitive?.content)

        val permissions = manifest["permissions"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue(permissions.containsAll(listOf("nativeMessaging", "nativeMessagingFromContent", "geckoViewAddons")))

        val contentJs = extensionAsset("content.js").readText(Charsets.UTF_8)
        // 只在主题键缺失时写入默认值（跟随系统），不覆盖用户修改过的主题
        assertTrue(contentJs.contains("localStorage.getItem('maibot-theme-mode') === null"))
        assertTrue(contentJs.contains("localStorage.setItem('maibot-theme-mode', 'system')"))
        assertTrue(contentJs.contains("localStorage.getItem('maibot-theme-dashboard-style') === null"))
        // 下载拦截在 page-world 脚本提前安装并连通 native port
        assertTrue(contentJs.contains("URL.createObjectURL = function"))
        assertTrue(contentJs.contains("browser.runtime.connectNative(NATIVE_APP)"))
        // 普通滚动区不统一封顶成一屏高；仅对话框限高且不按宽泛类名子串匹配
        assertFalse(contentJs.contains("max-height:var(--maidroid-webview-height)"))
        val dialogRule = contentJs.lines().single { it.contains("""'[role="dialog"]""") }
        assertTrue(dialogRule.contains("max-height:calc(var(--maidroid-webview-height) - 1rem)!important"))
        assertFalse(dialogRule.contains("[class*="))
    }

    @Test
    fun builtInExtensionConfiguresResizesContentAndFocusedInputReveal() {
        val manifestText = extensionAsset("manifest.json").readText(Charsets.UTF_8)
        val manifest = json.parseToJsonElement(manifestText).jsonObject
        val extensionVersion = manifest["version"]?.jsonPrimitive?.content
        // 递增扩展版本，确保已安装设备的 GeckoView ensureBuiltIn 自动更新脚本缓存
        assertFalse(extensionVersion.isNullOrBlank())
        assertFalse(extensionVersion == "1.0.0")
        assertEquals("1.0.3", extensionVersion)

        val contentJs = extensionAsset("content.js").readText(Charsets.UTF_8)
        // 横竖屏统一声明 resizes-content 并在视口收缩/切焦时自动居中显露被遮挡输入框
        assertTrue(contentJs.contains("interactive-widget=resizes-content"))
        assertTrue(contentJs.contains("withResizesContent"))
        assertTrue(contentJs.contains("ensureViewportResizesContent"))
        assertTrue(contentJs.contains("isControlClipped"))
        assertTrue(contentJs.contains("scrollIntoView"))
        assertTrue(contentJs.contains("focusin"))
        // 底色读取带原始色串缓存与防抖重入守卫，避免 SPA 高频 DOM 变更拖慢主线程
        assertTrue(contentJs.contains("lastRawBgPair"))
        assertTrue(contentJs.contains("isSyncingViewportMeta"))
        assertTrue(contentJs.contains("scheduleHeadSync"))
    }

    private fun extensionAsset(fileName: String): File =
        File("src/main/assets/extensions/maidroid/$fileName").takeIf { it.isFile }
            ?: File("app/src/main/assets/extensions/maidroid/$fileName").also {
                check(it.isFile) { "Built-in extension asset not found: $fileName" }
            }
}
