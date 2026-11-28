package org.maiwithu.maidroid.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.maiwithu.maidroid.runtime.GitHubMirror
import org.maiwithu.maidroid.runtime.GitHubMirrors
import java.util.concurrent.TimeUnit

internal sealed interface ManifestFetchResult {
    data object NotModified : ManifestFetchResult
    data class Updated(
        val rawEnvelopeJson: String,
        val manifest: RemoteManifest,
        val etag: String?
    ) : ManifestFetchResult
    data object Failed : ManifestFetchResult
}

/**
 * 远程公告与诊断服务的 HTTP 客户端：
 * - 拉取签名清单时先走 Worker 主域名（支持 ETag 304 省流），失败或验签不过时自动并发赛跑 GitHub 镜像兜底；
 * - 发送轻量 JSON 匿名运行统计与脱敏自动报错现场。
 */
internal class RemoteOpsClient(
    userAgent: String,
    private val gitHubMirrorsProvider: () -> List<GitHubMirror> = { GitHubMirrors.ALL },
    private val preferFirstMirror: () -> Boolean = { false }
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    suspend fun fetchManifest(
        baseUrl: String,
        fallbackUrl: String,
        cachedEtag: String?,
        extraKeysSpec: String
    ): ManifestFetchResult = withContext(Dispatchers.IO) {
        if (baseUrl.isNotBlank()) {
            val primary = fetchFromPrimary("$baseUrl/v1/manifest.json", cachedEtag, extraKeysSpec)
            if (primary !is ManifestFetchResult.Failed) return@withContext primary
        }
        if (fallbackUrl.isNotBlank()) {
            fetchFromFallbackMirrors(fallbackUrl, extraKeysSpec)?.let { return@withContext it }
        }
        ManifestFetchResult.Failed
    }

    private fun fetchFromPrimary(
        url: String,
        cachedEtag: String?,
        extraKeysSpec: String
    ): ManifestFetchResult = runCatching {
        val reqBuilder = Request.Builder().url(url).get()
        if (!cachedEtag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", cachedEtag)
        }
        http.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code == 304) return ManifestFetchResult.NotModified
            if (!resp.isSuccessful) return ManifestFetchResult.Failed
            val body = resp.body?.string().orEmpty()
            val verified = ManifestSignatureVerifier.verifyAndParse(body, extraKeysSpec)
                ?: return ManifestFetchResult.Failed
            ManifestFetchResult.Updated(body, verified, resp.header("ETag"))
        }
    }.getOrDefault(ManifestFetchResult.Failed)

    // 主源不可达时经 GitHub 镜像请求兜底清单：手动选源先单独尝试首选，自动模式或首选失败时并发赛跑其余候选
    private suspend fun fetchFromFallbackMirrors(
        fallbackUrl: String,
        extraKeysSpec: String
    ): ManifestFetchResult.Updated? {
        val mirrors = GitHubMirrors.candidatesFor(fallbackUrl, gitHubMirrorsProvider())
        if (mirrors.isEmpty()) return null
        if (preferFirstMirror() && mirrors.size > 1) {
            val preferred = raceManifestMirrors(listOf(mirrors.first()), fallbackUrl, extraKeysSpec)
            if (preferred != null) return preferred
            return raceManifestMirrors(mirrors.drop(1), fallbackUrl, extraKeysSpec)
        }
        return raceManifestMirrors(mirrors, fallbackUrl, extraKeysSpec)
    }

    private suspend fun raceManifestMirrors(
        mirrors: List<GitHubMirror>,
        fallbackUrl: String,
        extraKeysSpec: String
    ): ManifestFetchResult.Updated? = coroutineScope {
        if (mirrors.isEmpty()) return@coroutineScope null
        val pending = mirrors.map { mirror ->
            async(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(mirror.url(fallbackUrl)).get().build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string().orEmpty()
                        val manifest = ManifestSignatureVerifier.verifyAndParse(body, extraKeysSpec) ?: return@use null
                        ManifestFetchResult.Updated(body, manifest, etag = null)
                    }
                }.getOrNull()
            }
        }.toMutableList()

        var winner: ManifestFetchResult.Updated? = null
        while (pending.isNotEmpty() && winner == null) {
            val (done, result) = select {
                pending.forEach { job ->
                    job.onAwait { job to it }
                }
            }
            pending.remove(done)
            if (result != null) winner = result
        }
        pending.forEach { it.cancel() }
        winner
    }

    suspend fun sendTelemetry(baseUrl: String, jsonBody: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/telemetry")
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** 静默上报一条已脱敏的自动错误聚合现场，网络或服务异常时仅返回 false，绝不打断用户操作。 */
    suspend fun sendErrorReport(baseUrl: String, jsonBody: String): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/error-report")
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
