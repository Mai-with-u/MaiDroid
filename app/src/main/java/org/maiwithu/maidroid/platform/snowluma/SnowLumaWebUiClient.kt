package org.maiwithu.maidroid.platform.snowluma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** SnowLuma 汇报的一个 QQ 进程（WebUI 的 /api/processes），只取 MaiDroid 用得上的字段。 */
internal data class SnowLumaQqProcess(
    val pid: Int,
    val status: String,
    val injected: Boolean,
    val loggedIn: Boolean,
    val uin: String,
    val nickname: String,
    val error: String
)

/** 从 QQ 进程列表看出来的登录进度，二维码弹窗和已连接卡片按它提示用户。 */
internal enum class SnowLumaHookPhase {
    /** 还没找到 QQ 主进程：QQ 正在启动。 */
    QqStarting,

    /** 找到了 QQ，正在注入、连接。 */
    Injecting,

    /** 注入好了，等用户在 QQ 窗口里扫码或点登录。 */
    WaitingLogin,

    /** QQ 已登录。 */
    LoggedIn,

    /** 和 QQ 的连接断开了（QQ 退出或崩溃）。 */
    Disconnected,

    /** 注入失败，原因在 SnowLumaQqProcess.error。 */
    Failed
}

/**
 * 进程列表里挑一个最能说明情况的 QQ 进程：已登录的优先，其次注入好的，再其次任意一个。
 * SnowLuma 只列 QQ 主进程，正常只有一个；用户在 QQ 窗口里开了第二个 QQ 时才会有多个。
 */
internal fun primaryQqProcess(processes: List<SnowLumaQqProcess>): SnowLumaQqProcess? =
    processes.firstOrNull { it.loggedIn }
        ?: processes.firstOrNull { it.injected }
        ?: processes.firstOrNull()

/** 按 SnowLuma 的进程状态（available/loading/connecting/loaded/online/error/disconnected）归成登录进度。 */
internal fun snowLumaHookPhase(processes: List<SnowLumaQqProcess>): SnowLumaHookPhase {
    val process = primaryQqProcess(processes) ?: return SnowLumaHookPhase.QqStarting
    return when {
        process.loggedIn || process.status == "online" -> SnowLumaHookPhase.LoggedIn
        process.status == "error" -> SnowLumaHookPhase.Failed
        process.status == "disconnected" -> SnowLumaHookPhase.Disconnected
        process.status == "loaded" -> SnowLumaHookPhase.WaitingLogin
        else -> SnowLumaHookPhase.Injecting
    }
}

/** 解析 /api/processes 的返回；格式不对的项跳过。 */
internal fun parseSnowLumaProcesses(body: String): List<SnowLumaQqProcess> = runCatching {
    val json = Json { ignoreUnknownKeys = true }
    json.parseToJsonElement(body).jsonObject["list"]?.jsonArray.orEmpty().mapNotNull { element ->
        runCatching {
            val item = element.jsonObject
            val account = item["account"]?.let { runCatching { it.jsonObject }.getOrNull() }
            SnowLumaQqProcess(
                pid = item.string("pid")?.toIntOrNull() ?: return@runCatching null,
                status = item.string("status").orEmpty(),
                injected = item.bool("injected"),
                loggedIn = item.bool("loggedIn"),
                uin = item.string("uin")?.takeIf { it.isNotBlank() && it != "0" }
                    ?: account?.string("uin").orEmpty(),
                nickname = account?.string("nickname").orEmpty(),
                error = item.string("error").orEmpty()
            )
        }.getOrNull()
    }
}.getOrDefault(emptyList())

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.booleanOrNull == true

/**
 * 经 SnowLuma 自带 WebUI 的 HTTP 接口查 QQ 进程和登录状态。
 *
 * 登录：POST /api/login 提交密码（第一次启动时由 MaiDroid 生成的那个，见 SnowLumaScripts）换会话 token，
 * 之后的请求带 x-snowluma-token 头；token 24 小时有效，这里 23 小时内复用，被拒（SnowLuma 重启后旧 token 作废）就重新登录一次。
 * 登录接口输错 5 次会锁 15 分钟，所以密码被拒后先停 5 分钟不再试，免得把用户自己打开管理页的登录也锁住。
 */
internal class SnowLumaWebUiClient(private val files: SnowLumaFiles) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 1_000
        const val READ_TIMEOUT_MS = 5_000
        const val TOKEN_TTL_MS = 23 * 60 * 60 * 1_000L
        const val BAD_PASSWORD_BACKOFF_MS = 5 * 60 * 1_000L
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var token: String? = null

    @Volatile
    private var tokenIssuedAt = 0L

    @Volatile
    private var passwordRejectedAt = 0L

    /** SnowLuma 现在看到的 QQ 进程；WebUI 不通（SnowLuma 没起来、还在启动）时返回 null。 */
    suspend fun processes(): List<SnowLumaQqProcess>? = withContext(Dispatchers.IO) {
        runCatching { parseSnowLumaProcesses(getAuthorized("/api/processes")) }.getOrNull()
    }

    /**
     * SnowLuma 管理页的地址。带上 ?token=<密码> 时管理页会自动用它登录、随即从地址栏里去掉，用户不用手输密码；
     * 读不到密码时退回登录页。
     */
    fun pageUrl(): String {
        val base = "http://127.0.0.1:${port()}/"
        val password = files.readWebUiPassword()
        return if (password.isNullOrBlank()) base else "$base?token=${URLEncoder.encode(password, "UTF-8")}"
    }

    private fun port(): Int = files.readWebUiPort() ?: SnowLumaScripts.WEBUI_DEFAULT_PORT

    // 带会话 token 调 GET 接口；401 说明 token 作废了，重新登录再试一次。
    private fun getAuthorized(path: String): String {
        val first = request("GET", path, body = null, sessionToken = ensureToken())
        if (first.code != HttpURLConnection.HTTP_UNAUTHORIZED) return first.successBody(path)
        token = null
        return request("GET", path, body = null, sessionToken = ensureToken()).successBody(path)
    }

    @Synchronized
    private fun ensureToken(): String {
        val cached = token
        val now = System.currentTimeMillis()
        if (cached != null && now - tokenIssuedAt < TOKEN_TTL_MS) return cached
        if (now - passwordRejectedAt < BAD_PASSWORD_BACKOFF_MS) error("SnowLuma 管理页拒绝了 MaiDroid 保存的密码")
        val password = files.readWebUiPassword() ?: error("未找到 SnowLuma 管理页密码")
        val response = request("POST", "/api/login", buildJsonObject { put("password", password) }.toString(), null)
        val root = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            passwordRejectedAt = now
            error("SnowLuma 管理页拒绝了 MaiDroid 保存的密码")
        }
        val issued = root?.string("token")?.takeIf { root.bool("success") }
            ?: error("SnowLuma 管理页登录失败: ${root?.string("message").orEmpty()}")
        token = issued
        tokenIssuedAt = now
        return issued
    }

    private fun request(method: String, path: String, body: String?, sessionToken: String?): HttpResponse {
        val connection = URL("http://127.0.0.1:${port()}$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            sessionToken?.let { connection.setRequestProperty("x-snowluma-token", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private class HttpResponse(val code: Int, val body: String) {
        fun successBody(path: String): String {
            if (code >= 400) error("SnowLuma 接口 $path 返回 $code")
            return body
        }
    }
}
