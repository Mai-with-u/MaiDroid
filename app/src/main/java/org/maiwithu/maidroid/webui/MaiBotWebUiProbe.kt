package org.maiwithu.maidroid.webui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 探测容器内 MaiBot WebUI 是否已经能响应 HTTP，OOBE 等待启动和主页在线状态共用这一处。 */
internal object MaiBotWebUiProbe {
    private const val TIMEOUT_MS = 1_000

    /** 只要 WebUI 返回任意非 5xx 响应就视为在线：未登录时的 401/404 也说明服务已经起来了。 */
    suspend fun isReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(MaiBotWebUi.URL)
                .openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.responseCode in 200..499
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }
}
