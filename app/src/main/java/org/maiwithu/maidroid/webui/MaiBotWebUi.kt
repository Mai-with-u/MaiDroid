package org.maiwithu.maidroid.webui

import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.terminal.AnsiText
import java.net.HttpURLConnection
import java.net.URL

/**
 * MaiBot 自带的 WebUI：地址、是否在线、免登录打开用的访问令牌。
 * 容器与宿主共用网络，宿主直接访问回环地址即可。
 */
internal object MaiBotWebUi {
    const val URL = "http://127.0.0.1:8001/"

    /** WebView 加载、console 输出、视口修补结果都打到这个 logcat 标签。 */
    const val LOG_TAG = "MaiDroidWebUi"

    private const val PROBE_TIMEOUT_MS = 1_000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // MaiBot 启动时在日志里打印的访问令牌，例如“WebUI access token: abc123”。
    private val tokenLogRegex = Regex("""(?i)WebUI[^\r\n]*Token:\s*([^\s]+)""")

    /**
     * 探测 WebUI 是否已经能响应 HTTP。OOBE 等待启动和主页在线状态共用这一处。
     * 只要返回任意非 5xx 响应就视为在线：未登录时的 401/404 也说明服务已经起来了。
     */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.responseCode in 200..499
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    /** url 是否指向 MaiBot WebUI（而不是 NapCat 管理页等其他页面），只有它需要带令牌打开。 */
    fun isMaiBotWebUiUrl(url: String): Boolean {
        val normalizedUrl = url.trimEnd('/')
        val normalizedMaiBotUrl = URL.trimEnd('/')
        return normalizedUrl == normalizedMaiBotUrl || normalizedUrl.startsWith("$normalizedMaiBotUrl/")
    }

    /**
     * 打开 WebUI 时实际加载的地址：找得到访问令牌就打开 /auth?token=…，让 WebUI 自动登录；找不到就打开首页。
     * 令牌优先读 MaiBot 的 data/webui.json，读不到再从终端日志里找最近一次打印的。
     */
    fun launchUrl(
        paths: RuntimePaths,
        terminalLogs: List<String>,
        baseUrl: String = URL
    ): String {
        val token = readAccessTokenFromConfig(paths) ?: extractAccessTokenFromLogs(terminalLogs) ?: return baseUrl
        return baseUrl.toUri()
            .buildUpon()
            .encodedPath("/auth")
            .clearQuery()
            .fragment(null)
            .appendQueryParameter("token", token)
            .build()
            .toString()
    }

    /**
     * 从日志里取最近一次打印的访问令牌（MaiBot 重启后令牌可能变化，所以从后往前找）。
     * 终端日志里还留着 SGR 颜色序列，先转成纯文本再匹配，否则令牌后面紧跟的 ESC[0m 会被当成令牌的一部分。
     */
    fun extractAccessTokenFromLogs(terminalLogs: List<String>): String? =
        terminalLogs.asReversed().firstNotNullOfOrNull { line ->
            tokenLogRegex.find(AnsiText.plain(line))
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

    /** 打日志前把地址里的 token 换成 <redacted>，避免访问令牌出现在 logcat 里。 */
    fun redactUrlForLogs(url: String): String =
        runCatching {
            val uri = url.toUri()
            if (uri.getQueryParameter("token") == null) {
                url
            } else {
                uri.buildUpon()
                    .clearQuery()
                    .appendQueryParameter("token", "<redacted>")
                    .build()
                    .toString()
            }
        }.getOrElse {
            url.replace(Regex("""token=[^&#]+"""), "token=<redacted>")
        }

    private fun readAccessTokenFromConfig(paths: RuntimePaths): String? {
        val tokenFile = paths.maiBotWebUiConfig
        if (!tokenFile.isFile) return null

        return runCatching {
            val root = json.parseToJsonElement(tokenFile.readText(Charsets.UTF_8)).jsonObject
            root["access_token"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrElse { error ->
            Log.w(LOG_TAG, "Failed to read WebUI access token from ${tokenFile.absolutePath}: ${error.message}")
            null
        }
    }
}
