package org.maiwithu.maidroid.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** NapCat 后端汇报的 QQ 登录进度，取自 WebUI 的 CheckLoginStatus 接口。 */
internal data class NapCatLoginStatus(
    val isLogin: Boolean,
    val phase: String,
    val qrCodeUrl: String,
    val loginError: String
)

/**
 * 经 NapCat 自带 WebUI 的 HTTP 接口查询扫码进度、要求 QQ 重新生成二维码。
 *
 * 二维码图片由 NapCat 写到 cache/qrcode.png，单纯重读文件拿不到新码；只有调 RefreshQRcode，
 * NapCat 才会让 QQ 登录服务重新出码并覆盖这个文件。
 */
internal class NapCatWebUiClient(private val webUiConfigFile: File) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_000
        // RefreshQRcode 会在服务端最多等 10 秒新码，读超时要比它长。
        const val READ_TIMEOUT_MS = 15_000
        // NapCat 只认一小时内签发的凭证，提前十分钟换新。
        const val CREDENTIAL_TTL_MS = 50 * 60 * 1_000L
        const val DEFAULT_PORT = 6099
    }

    private val json = Json { ignoreUnknownKeys = true }
    // 弹窗里的自动轮询和用户点刷新可能同时发请求，凭证读写加锁。
    @Volatile
    private var credential: String? = null
    @Volatile
    private var credentialIssuedAt = 0L

    suspend fun loginStatus(): NapCatLoginStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val data = callAuthorized("/api/QQLogin/CheckLoginStatus").data ?: return@runCatching null
            NapCatLoginStatus(
                isLogin = data["isLogin"]?.jsonPrimitive?.booleanOrNull == true,
                phase = data["loginPhase"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                qrCodeUrl = data["qrcodeurl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                loginError = data["loginError"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.getOrNull()
    }

    /** 成功时 NapCat 已把新码写进 qrcode.png；失败时返回可直接展示给用户的原因。 */
    suspend fun refreshQrCode(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = try {
                callAuthorized("/api/QQLogin/RefreshQRcode")
            } catch (e: ConnectException) {
                // NapCat 没起来或 WebUI 还没监听端口，给用户一句能看懂的话，不抛底层异常文本。
                error("NapCat 未运行或尚未就绪")
            }
            if (!response.success) error(response.message.ifBlank { "NapCat 拒绝刷新二维码" })
            // QQ 登录服务正在重连时 NapCat 不出码，而是回 restarting，等它重连后会自己出新码。
            if (response.data?.get("restarting")?.jsonPrimitive?.booleanOrNull == true) {
                error("QQ 登录服务正在重启，稍后会自动生成新二维码")
            }
        }
    }

    // 带凭证调接口；凭证过期或被 NapCat 重启作废时，重新换一次凭证再试。
    private fun callAuthorized(path: String): ApiResponse {
        val first = post(path, authorization = ensureCredential())
        if (first.success || first.message != "Unauthorized") return first
        credential = null
        return post(path, authorization = ensureCredential())
    }

    // 用 webui.json 里的 token 换取凭证：NapCat 要求提交 sha256(token + ".napcat")。
    // 登录接口每分钟限 10 次，所以凭证要缓存起来复用。
    @Synchronized
    private fun ensureCredential(): String {
        val cached = credential
        if (cached != null && System.currentTimeMillis() - credentialIssuedAt < CREDENTIAL_TTL_MS) {
            return cached
        }
        val token = readWebUiConfig()["token"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: error("未找到 NapCat WebUI token")
        val response = post(
            path = "/api/auth/login",
            body = buildJsonObject { put("hash", sha256Hex("$token.napcat")) }.toString()
        )
        val issued = response.data?.get("Credential")?.jsonPrimitive?.contentOrNull
            ?: error("NapCat WebUI 登录失败: ${response.message}")
        credential = issued
        credentialIssuedAt = System.currentTimeMillis()
        return issued
    }

    private fun post(path: String, body: String = "{}", authorization: String? = null): ApiResponse {
        val connection = URL("http://127.0.0.1:${readPort()}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            authorization?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (connection.responseCode < 400) connection.inputStream else connection.errorStream
            val root = json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
            return ApiResponse(
                success = root["code"]?.jsonPrimitive?.intOrNull == 0,
                message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                data = runCatching { root["data"]?.jsonObject }.getOrNull()
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readPort(): Int =
        runCatching { readWebUiConfig()["port"]?.jsonPrimitive?.intOrNull }.getOrNull() ?: DEFAULT_PORT

    private fun readWebUiConfig(): JsonObject =
        json.parseToJsonElement(webUiConfigFile.readText()).jsonObject

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private class ApiResponse(
        val success: Boolean,
        val message: String,
        val data: JsonObject?
    )
}
