package org.maiwithu.maidroid.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** 待测速的单个镜像条目（内部键值、显示名称与探测地址）。 */
internal data class MirrorProbeTarget(
    val key: String,
    val displayName: String,
    val url: String
)

/** 校验 GitHub smart HTTP info/refs 探针响应是否真正来自可用的 git 代理，拦截误返回 HTML 页的失效镜像。 */
internal fun isValidGitHubProbeResponse(
    code: Int,
    contentType: String?,
    bodyPrefix: String
): Boolean {
    if (code !in 200..299) return false
    if (contentType?.contains("text/html", ignoreCase = true) == true) return false
    val hasGitMime = contentType?.contains("git-upload-pack", ignoreCase = true) == true
    val hasServiceMarker = bodyPrefix.contains("service=git-upload-pack")
    return hasGitMime || hasServiceMarker
}

/** 校验 Debian Release 探针响应，确保返回真实 Debian 发行元信息而非代理错误页。 */
internal fun isValidAptProbeResponse(
    code: Int,
    bodyPrefix: String
): Boolean {
    if (code != 200 && code != 206) return false
    return bodyPrefix.contains("Origin: Debian", ignoreCase = true) ||
        bodyPrefix.contains("Codename:", ignoreCase = true) ||
        bodyPrefix.contains("Suite:", ignoreCase = true)
}

/** 校验 PyPI Simple API `/pip/` 探针响应，确保是 PEP 503/691 包索引页而非 404/网关错误页。 */
internal fun isValidPyPiProbeResponse(
    code: Int,
    bodyPrefix: String
): Boolean {
    if (code !in 200..299) return false
    if (bodyPrefix.contains("<title>404", ignoreCase = true)) return false
    return bodyPrefix.contains("pip-", ignoreCase = true) ||
        bodyPrefix.contains("\"name\"", ignoreCase = true) ||
        bodyPrefix.contains("Links for pip", ignoreCase = true)
}

/**
 * 宿主侧并发镜像测速引擎：
 * 使用短超时（4s 读 / 5s 总限）与极小首包校验（仅读前 2KB），并发探测各预设与自定义源并实时吐出可用延迟。
 */
internal class MirrorSpeedTester(
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private companion object {
        const val CONNECT_TIMEOUT_SEC = 4L
        const val READ_TIMEOUT_SEC = 4L
        const val CALL_TIMEOUT_SEC = 5L
        const val PROBE_PREFIX_BYTES = 2048
        const val GITHUB_PROBE_TARGET = "https://github.com/Mai-with-u/MaiBot.git/info/refs?service=git-upload-pack"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 构建某分类的内置与可选自定义探测目标，供测速页与自动优选共用。 */
    fun targetsFor(category: MirrorCategory, extraCustomUrl: String? = null): List<MirrorProbeTarget> {
        val builtIn = when (category) {
            MirrorCategory.GitHub -> GitHubMirrors.ALL.map { mirror ->
                MirrorProbeTarget(
                    key = mirror.prefix,
                    displayName = mirror.name,
                    url = mirror.url(GITHUB_PROBE_TARGET)
                )
            }
            MirrorCategory.Apt -> AptMirrors.ALL.map { mirror ->
                MirrorProbeTarget(
                    key = mirror.baseUrl,
                    displayName = mirror.name,
                    url = "${mirror.baseUrl.trimEnd('/')}/debian/dists/bookworm/Release"
                )
            }
            MirrorCategory.PyPi -> PyPiMirrors.ALL.map { mirror ->
                MirrorProbeTarget(
                    key = mirror.indexUrl,
                    displayName = mirror.name,
                    url = "${mirror.probeUrl.trimEnd('/')}/pip/"
                )
            }
        }
        val cleanCustom = extraCustomUrl?.takeIf { url ->
            url.isNotBlank() && builtIn.none { it.key == url }
        } ?: return builtIn

        val customTarget = when (category) {
            MirrorCategory.GitHub -> MirrorProbeTarget(
                key = cleanCustom,
                displayName = "自定义镜像",
                url = "${cleanCustom.trimEnd('/')}/$GITHUB_PROBE_TARGET"
            )
            MirrorCategory.Apt -> MirrorProbeTarget(
                key = cleanCustom,
                displayName = "自定义软件源",
                url = "${cleanCustom.trimEnd('/')}/debian/dists/bookworm/Release"
            )
            MirrorCategory.PyPi -> MirrorProbeTarget(
                key = cleanCustom,
                displayName = "自定义 PyPI 源",
                url = "${cleanCustom.trimEnd('/')}/pip/"
            )
        }
        return builtIn + customTarget
    }

    /** 并发测速某个分类下全部目标，每条完成后立即回调 [onResult] 供界面渐进点亮毫秒徽章。 */
    suspend fun probeCategory(
        category: MirrorCategory,
        extraCustomUrl: String? = null,
        onResult: (MirrorProbeResult) -> Unit = {}
    ): Map<String, MirrorProbeResult> = withContext(Dispatchers.IO) {
        val targets = targetsFor(category, extraCustomUrl)
        val lock = Any()
        val collected = LinkedHashMap<String, MirrorProbeResult>(targets.size)
        coroutineScope {
            targets.forEach { target ->
                launch {
                    val result = executeProbe(category, target)
                    synchronized(lock) {
                        collected[target.key] = result
                    }
                    onResult(result)
                }
            }
        }
        collected
    }

    /** 对用户刚刚输入的单条已校验镜像地址执行即时测速。 */
    suspend fun probeCustomUrl(
        category: MirrorCategory,
        normalizedUrl: String
    ): MirrorProbeResult = withContext(Dispatchers.IO) {
        val probeUrl = when (category) {
            MirrorCategory.GitHub -> "${normalizedUrl.trimEnd('/')}/$GITHUB_PROBE_TARGET"
            MirrorCategory.Apt -> "${normalizedUrl.trimEnd('/')}/debian/dists/bookworm/Release"
            MirrorCategory.PyPi -> "${normalizedUrl.trimEnd('/')}/pip/"
        }
        executeProbe(
            category = category,
            target = MirrorProbeTarget(
                key = normalizedUrl,
                displayName = "自定义",
                url = probeUrl
            )
        )
    }

    private fun executeProbe(category: MirrorCategory, target: MirrorProbeTarget): MirrorProbeResult {
        val requestBuilder = Request.Builder().url(target.url).get()
        when (category) {
            MirrorCategory.GitHub -> requestBuilder.header("User-Agent", "git/2.43.0")
            MirrorCategory.Apt -> requestBuilder.header("Range", "bytes=0-1023")
            MirrorCategory.PyPi -> requestBuilder.header("Accept", "application/vnd.pypi.simple.v1+json, text/html;q=0.9")
        }
        val startedNanos = nowNanos()
        val testedAt = nowEpochMs()
        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                val contentType = response.header("Content-Type")
                val prefix = readPrefix(response.body?.byteStream(), PROBE_PREFIX_BYTES)
                val elapsedMs = ((nowNanos() - startedNanos) / 1_000_000L).coerceAtLeast(1L)
                val valid = when (category) {
                    MirrorCategory.GitHub -> isValidGitHubProbeResponse(code, contentType, prefix)
                    MirrorCategory.Apt -> isValidAptProbeResponse(code, prefix)
                    MirrorCategory.PyPi -> isValidPyPiProbeResponse(code, prefix)
                }
                if (valid) {
                    MirrorProbeResult(
                        key = target.key,
                        status = MirrorProbeStatus.Reachable,
                        latencyMs = elapsedMs,
                        testedAtMs = testedAt
                    )
                } else {
                    MirrorProbeResult(
                        key = target.key,
                        status = MirrorProbeStatus.Failed,
                        errorReason = if (code in 200..299) "响应无效" else "HTTP $code",
                        testedAtMs = testedAt
                    )
                }
            }
        } catch (e: IOException) {
            MirrorProbeResult(
                key = target.key,
                status = MirrorProbeStatus.Failed,
                errorReason = describeProbeFailure(e),
                testedAtMs = testedAt
            )
        }
    }

    private fun readPrefix(input: InputStream?, limit: Int): String {
        if (input == null) return ""
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, total, limit - total)
            if (read <= 0) break
            total += read
        }
        return if (total <= 0) "" else String(buffer, 0, total, Charsets.UTF_8)
    }

    private fun describeProbeFailure(error: IOException): String = when (error) {
        is UnknownHostException -> "域名解析失败"
        is SocketTimeoutException -> "连接超时"
        is SSLException -> "TLS 握手失败"
        else -> {
            val msg = error.message.orEmpty()
            when {
                msg.contains("timeout", ignoreCase = true) -> "连接超时"
                msg.contains("refused", ignoreCase = true) -> "连接被拒绝"
                else -> "无法连通"
            }
        }
    }
}
