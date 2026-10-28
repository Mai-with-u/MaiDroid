package org.maiwithu.maidroid.platform.napcat

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
import java.net.URLEncoder
import java.security.MessageDigest

/** CheckLoginStatus 接口返回的 loginPhase；NapCat 新增或改名的取值统一归为 Other。 */
internal enum class NapCatLoginPhase(val wireValue: String) {
    WaitingQrCode("waiting_qrcode"),
    GeneratingQrCode("generating_qrcode"),
    QrCodeScanned("qrcode_scanned"),
    Initializing("initializing"),
    Reconnecting("reconnecting"),
    Ready("ready"),
    Other("");

    companion object {
        fun fromWireValue(value: String): NapCatLoginPhase =
            entries.firstOrNull { it != Other && it.wireValue == value } ?: Other
    }
}

/** NapCat 后端汇报的 QQ 登录进度，取自 WebUI 的 CheckLoginStatus 接口。 */
internal data class NapCatLoginStatus(
    val isLogin: Boolean,
    val phase: NapCatLoginPhase,
    val loginError: String
)

/** NapCat 当前登录的 QQ 账号，取自 WebUI 的 GetQQLoginInfo 接口。 */
internal data class NapCatLoginAccount(
    val uin: String,
    val nick: String
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
                phase = NapCatLoginPhase.fromWireValue(data["loginPhase"]?.jsonPrimitive?.contentOrNull.orEmpty()),
                loginError = data["loginError"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.getOrNull()
    }

    /** 当前在线的 QQ 账号；未登录、已掉线或 NapCat 不通时为 null。 */
    suspend fun loginAccount(): NapCatLoginAccount? = withContext(Dispatchers.IO) {
        runCatching {
            val data = callAuthorized("/api/QQLogin/GetQQLoginInfo").data ?: return@runCatching null
            if (data["online"]?.jsonPrimitive?.booleanOrNull != true) return@runCatching null
            val uin = data["uin"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() && it != "0" }
                ?: return@runCatching null
            NapCatLoginAccount(uin = uin, nick = data["nick"]?.jsonPrimitive?.contentOrNull.orEmpty())
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

    /** 清空 NapCat 的自动登录账号：退出 QQ 后重启 NapCat 时不再自动快速登录回原账号。 */
    suspend fun clearAutoLoginAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = callAuthorized(
                path = "/api/QQLogin/SetQuickLoginQQ",
                body = buildJsonObject { put("uin", "") }.toString()
            )
            if (!response.success) error(response.message.ifBlank { "NapCat 拒绝修改自动登录账号" })
        }
    }

    /**
     * NapCat 自带管理页的地址。前端路由的 basename 是 /webui/，少了结尾斜杠整页不渲染（白屏）；
     * 带上 ?token= 时前端未登录会自动用它登录，用户不用手输 token。读不到 token 时退回登录页。
     */
    fun pageUrl(): String {
        val base = "http://127.0.0.1:${readPort()}/webui/"
        val token = runCatching { readWebUiConfig()["token"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return if (token.isNullOrBlank()) base else "$base?token=${URLEncoder.encode(token, "UTF-8")}"
    }

    // 带凭证调接口；凭证过期或被 NapCat 重启作废时，重新换一次凭证再试。
    private fun callAuthorized(path: String, body: String = "{}"): ApiResponse {
        val first = post(path, body = body, authorization = ensureCredential())
        if (first.success || first.message != "Unauthorized") return first
        credential = null
        return post(path, body = body, authorization = ensureCredential())
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
