package org.maiwithu.maidroid.webui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * WebUI 与内置 GeckoView WebExtension（`content.js`）之间双向通信的纯 Kotlin JSON 协议层。
 * 保持零 Android / org.json / GeckoView 依赖，确保全部消息编码与解析都能直接跑 JVM 单测。
 */
internal sealed interface WebUiBridgeEvent {
    data class PageBackground(val argb: Int) : WebUiBridgeEvent
    data class TwoFingerResult(val canContinue: Boolean, val generation: Int) : WebUiBridgeEvent
    data class BlobBegin(val fileName: String, val mimeType: String, val sizeBytes: Long) : WebUiBridgeEvent
    data class BlobAppend(val id: String, val base64: String) : WebUiBridgeEvent
    data class BlobFinish(val id: String) : WebUiBridgeEvent
    data class BlobFail(val id: String, val message: String?) : WebUiBridgeEvent
}

internal object WebUiBridgeProtocol {
    const val NATIVE_APP_NAME = "maidroid"
    const val EXTENSION_LOCATION = "resource://android/assets/extensions/maidroid/"
    const val EXTENSION_ID = "maidroid-bridge@maiwithu.org"

    private val json = Json { ignoreUnknownKeys = true }

    /** 构造下发给扩展的页面配置：横屏传桌面版布局宽度（并关闭竖屏高度补丁），竖屏传 null。 */
    fun configureJsonText(desktopWidthCss: Int?, enableViewportPatch: Boolean): String =
        buildJsonObject {
            put("type", "configure")
            if (desktopWidthCss != null) {
                put("desktopWidthCss", desktopWidthCss)
            } else {
                put("desktopWidthCss", JsonNull)
            }
            put("enableViewportPatch", enableViewportPatch)
        }.toString()

    /** 构造两指外层滚动起手消息。 */
    fun twoFingerBeginJsonText(x: Float, y: Float, viewWidthPx: Int): String =
        buildJsonObject {
            put("type", "twoFingerBegin")
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("viewWidth", viewWidthPx)
        }.toString()

    /** 构造两指位移与惯性帧消息；拖动手势时 generation 为 null，惯性帧传当前代数以便回执匹配。 */
    fun twoFingerMoveJsonText(dy: Float, generation: Int? = null): String =
        buildJsonObject {
            put("type", "twoFingerMove")
            put("dy", dy.toDouble())
            if (generation != null) {
                put("generation", generation)
            }
        }.toString()

    /** 构造 Blob 下载 begin 握手回执，id 为空字符串时扩展侧停止继续分块。 */
    fun blobBeginReplyJsonText(id: String?): String =
        buildJsonObject {
            put("id", id.orEmpty())
            put("ok", !id.isNullOrEmpty())
        }.toString()

    /** 构造 Blob append / finish / fail 通用回执。 */
    fun blobStatusReplyJsonText(ok: Boolean): String =
        buildJsonObject {
            put("ok", ok)
        }.toString()

    /** 解析扩展发回的 JSON 字符串消息；格式无效或类型未知时返回 null。 */
    fun parseEvent(rawJson: String): WebUiBridgeEvent? {
        val obj = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
        return parseEvent(obj)
    }

    internal fun parseEvent(obj: JsonObject): WebUiBridgeEvent? {
        val type = obj["type"]?.jsonPrimitive?.contentOrNullIfNull() ?: return null
        return when (type) {
            "pageBackground" -> {
                val r = obj.intChannel("r") ?: return null
                val g = obj.intChannel("g") ?: return null
                val b = obj.intChannel("b") ?: return null
                val a = obj.intChannel("a") ?: return null
                WebUiBridgeEvent.PageBackground((a shl 24) or (r shl 16) or (g shl 8) or b)
            }
            "twoFingerResult" -> {
                val canContinue = obj["canContinue"]?.jsonPrimitive?.booleanOrNull ?: return null
                val generation = obj["generation"]?.jsonPrimitive?.intOrNull ?: return null
                WebUiBridgeEvent.TwoFingerResult(canContinue, generation)
            }
            "blobBegin" -> {
                val fileName = obj["fileName"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                val mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                val sizeBytes = obj["sizeBytes"]?.jsonPrimitive?.longOrNull
                    ?: obj["sizeBytes"]?.jsonPrimitive?.doubleOrNull?.toLong()
                    ?: return null
                WebUiBridgeEvent.BlobBegin(fileName, mimeType, sizeBytes)
            }
            "blobAppend" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                val base64 = obj["base64"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                WebUiBridgeEvent.BlobAppend(id, base64)
            }
            "blobFinish" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                WebUiBridgeEvent.BlobFinish(id)
            }
            "blobFail" -> {
                val id = obj["id"]?.jsonPrimitive?.contentOrNullIfNull().orEmpty()
                val message = obj["message"]?.jsonPrimitive?.contentOrNullIfNull()
                WebUiBridgeEvent.BlobFail(id, message)
            }
            else -> null
        }
    }

    private fun JsonObject.intChannel(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..255 }

    private fun JsonPrimitive.contentOrNullIfNull(): String? =
        if (this is JsonNull || !isString && content == "null") null else content
}
