package org.maiwithu.maidroid.appupdate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.maiwithu.maidroid.runtime.GitHubMirror
import org.maiwithu.maidroid.runtime.GitHubMirrors
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 按最近 3 秒的进度样本算下载速度，避免每 200 毫秒跳一次。换线路重新开始时 reset。
 * 只在下载所在的那个 IO 线程上调用，不用加锁。
 */
internal class DownloadSpeedMeter(private val windowNanos: Long = 3_000_000_000L) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() {
        samples.clear()
    }

    /** 记一个样本（时间纳秒、已下载字节），返回每秒字节数；样本不够时返回 0。 */
    fun sample(nowNanos: Long, downloadedBytes: Long): Long {
        samples.addLast(nowNanos to downloadedBytes)
        while (samples.size > 2 && nowNanos - samples.first().first > windowNanos) samples.removeFirst()
        val (startNanos, startBytes) = samples.first()
        val elapsed = nowNanos - startNanos
        if (elapsed <= 0) return 0
        return (downloadedBytes - startBytes).coerceAtLeast(0) * 1_000_000_000L / elapsed
    }
}

/** 赛跑中胜出的线路和它给出的结果。 */
internal data class MirrorWin<T>(val mirror: GitHubMirror, val value: T)

/** 下载过程的回调，都在 IO 线程上调用。 */
internal interface ApkDownloadListener {
    /** 这一轮选中了哪条线路、从第几个字节开始下。 */
    fun onSourceChosen(source: String, startByte: Long)

    /** 进度，大约每 200 毫秒一次，结束时再报一次。totalBytes 未知时为 0。 */
    fun onProgress(downloadedBytes: Long, totalBytes: Long, source: String)

    /** 这条线路中途断开或卡住，接下来会重新选线路接着下。 */
    fun onSourceFailed(source: String, reason: String)
}

/**
 * 应用自更新的网络部分：查 GitHub 最新 Release、下载 APK。
 *
 * 国内直连 GitHub 不稳，两件事都同时向直连和所有镜像发请求（[GitHubMirrors.candidatesFor]），
 * 谁先给出有效结果就用谁，其余请求立刻取消：
 * - 查版本：先返回能解析的 Release JSON 的线路胜出（不代理 api.github.com 的镜像回 403，自然落选）；
 * - 下载：每条线路都从断点处发 Range 请求，先收满 512 KB 的胜出并接着把剩下的下完，
 *   这样选中的是此刻真正最快的线路；中途断开或 20 秒没有数据，就排除这条线路重新赛跑，从新的断点接着下。
 * 安装包直接流式写进 .part 文件，不整个读进内存。
 */
internal class AppUpdateClient(userAgent: String) {
    private companion object {
        const val CHECK_TIMEOUT_MS = 20_000L
        const val PROBE_TIMEOUT_MS = 45_000L
        const val PROBE_BYTES = 512 * 1024
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_RELEASE_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_ROUNDS_WITHOUT_PROGRESS = 4
        const val ROUND_RETRY_DELAY_MS = 2_000L
        const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }

    // 读超时兼做卡住检测：连上后 20 秒收不到任何数据就判这条线路失败。
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    @Volatile
    private var streamingCall: Call? = null

    /** 取消下载时调用：掐断正在流式读取的请求，让阻塞中的读立刻抛出异常退出。 */
    fun abortDownload() {
        streamingCall?.cancel()
    }

    /** 查最新正式版 Release；所有线路都失败或都不是有效的 Release 时返回 null。 */
    suspend fun fetchLatestRelease(apiUrl: String): MirrorWin<AppRelease>? = withContext(Dispatchers.IO) {
        raceMirrors(
            mirrors = GitHubMirrors.candidatesFor(apiUrl),
            timeoutMs = CHECK_TIMEOUT_MS,
            request = { mirror ->
                Request.Builder()
                    .url(mirror.url(apiUrl))
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
            },
            accept = { _, _, response ->
                response.use {
                    val body = it.body
                    if (!it.isSuccessful || body == null) null else readCapped(body)?.let(::parseAppRelease)
                }
            }
        )
    }

    /**
     * 把 asset 下载到 partFile，已有的部分接着下。正常返回表示下完了（大小与 Release 一致）；
     * 连续几轮都没有任何进展时抛出 IOException，.part 保留，下次重试接着下。
     */
    suspend fun downloadApk(
        asset: AppReleaseAsset,
        partFile: File,
        listener: ApkDownloadListener
    ) = withContext(Dispatchers.IO) {
        partFile.parentFile?.mkdirs()
        val total = asset.size
        val allMirrors = GitHubMirrors.candidatesFor(asset.downloadUrl)
        var excluded: GitHubMirror? = null
        var roundsWithoutProgress = 0

        while (true) {
            ensureActive()
            val start = partFile.length()
            if (total > 0 && start == total) return@withContext
            if (total > 0 && start > total) {
                // 比 Release 里的大小还大，肯定不是这个文件，从头下
                partFile.delete()
                continue
            }

            // 刚断开的线路这一轮先不参加，只剩它一条时照样用它
            val candidates = allMirrors.filter { it != excluded }.ifEmpty { allMirrors }
            val win = raceMirrors(
                mirrors = candidates,
                timeoutMs = PROBE_TIMEOUT_MS,
                request = { mirror ->
                    Request.Builder()
                        .url(mirror.url(asset.downloadUrl))
                        .header("Range", "bytes=$start-")
                        .build()
                },
                accept = { _, call, response -> openProbe(call, response, start, total) },
                discard = { it.close() }
            )

            if (win == null) {
                roundsWithoutProgress++
                if (roundsWithoutProgress >= MAX_ROUNDS_WITHOUT_PROGRESS) {
                    throw IOException("GitHub 和所有镜像都连不上")
                }
                excluded = null
                delay(ROUND_RETRY_DELAY_MS)
                continue
            }

            val source = win.mirror.name
            val probe = win.value
            listener.onSourceChosen(source, probe.bodyStart)
            var written = 0L
            try {
                streamingCall = probe.call
                // 服务器不支持断点续传、从头发了整个文件时，覆盖掉已有的部分
                FileOutputStream(partFile, probe.bodyStart > 0).use { output ->
                    var downloaded = probe.bodyStart
                    var lastReport = 0L
                    fun write(bytes: ByteArray, count: Int) {
                        output.write(bytes, 0, count)
                        downloaded += count
                        written += count
                        val now = System.nanoTime()
                        if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                            listener.onProgress(downloaded, total, source)
                            lastReport = now
                        }
                    }

                    write(probe.firstChunk, probe.firstChunk.size)
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        ensureActive()
                        val read = probe.input.read(buffer)
                        if (read < 0) break
                        write(buffer, read)
                    }
                    listener.onProgress(downloaded, total, source)
                }
                // 大小未知时只能以连接正常结束为准，后面的 SHA-256 和安装包核对会兜住不完整的文件
                if (total <= 0 || partFile.length() == total) return@withContext
                throw IOException("连接提前断开")
            } catch (e: IOException) {
                // 用户取消时请求被掐断也会走到这里，这时直接按取消退出
                ensureActive()
                listener.onSourceFailed(source, e.message ?: e.javaClass.simpleName)
                excluded = win.mirror
                roundsWithoutProgress = if (written > 0) 0 else roundsWithoutProgress + 1
                if (roundsWithoutProgress >= MAX_ROUNDS_WITHOUT_PROGRESS) {
                    throw IOException("下载反复中断：${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                streamingCall = null
                probe.close()
            }
        }
    }

    /** 一条线路的下载响应和它已经收到的前 512 KB；赢下赛跑的接着读 input。 */
    private class Probe(
        val call: Call,
        val response: Response,
        val input: InputStream,
        val bodyStart: Long,
        val firstChunk: ByteArray
    ) : Closeable {
        override fun close() {
            call.cancel()
            response.close()
        }
    }

    // 响应能接在断点后面才读前 512 KB；一个字节都没收到（又还没到文件末尾）不算有效。
    private fun openProbe(call: Call, response: Response, start: Long, total: Long): Probe? {
        val body = response.body ?: return null
        val bodyStart = rangeResponseStart(
            code = response.code,
            contentRange = response.header("Content-Range"),
            contentLength = body.contentLength(),
            contentType = response.header("Content-Type"),
            requestedStart = start,
            expectedTotal = total
        ) ?: return null
        val input = body.byteStream()
        val chunk = readUpTo(input, PROBE_BYTES)
        if (chunk.isEmpty() && (total <= 0 || bodyStart < total)) return null
        return Probe(call, response, input, bodyStart, chunk)
    }

    /**
     * 同时向每条线路发请求，accept 返回非 null 的第一个胜出，其余请求立刻取消。
     * accept 在 IO 线程上阻塞执行；返回 null 或抛异常时响应由这里关闭。
     * 胜出结果在超时或被取消之后才到的，交给 discard 释放（下载时要关掉连接）。
     */
    private suspend fun <T : Any> raceMirrors(
        mirrors: List<GitHubMirror>,
        timeoutMs: Long,
        request: (GitHubMirror) -> Request,
        accept: (GitHubMirror, Call, Response) -> T?,
        discard: (T) -> Unit = {}
    ): MirrorWin<T>? = coroutineScope {
        val lock = Any()
        var decided = false
        var winner: MirrorWin<T>? = null
        val finished = CompletableDeferred<Unit>()
        val calls = mirrors.map { it to client.newCall(request(it)) }

        val attempts = calls.map { (mirror, call) ->
            launch(Dispatchers.IO) {
                val value = attempt(mirror, call, accept) ?: return@launch
                val won = synchronized(lock) {
                    if (decided) {
                        false
                    } else {
                        decided = true
                        winner = MirrorWin(mirror, value)
                        true
                    }
                }
                if (won) finished.complete(Unit) else discard(value)
            }
        }
        launch {
            attempts.joinAll()
            finished.complete(Unit)
        }

        var completedNormally = false
        try {
            withTimeoutOrNull(timeoutMs) { finished.await() }
            completedNormally = true
        } finally {
            val chosen = synchronized(lock) {
                decided = true
                winner
            }
            calls.forEach { (mirror, call) ->
                if (!completedNormally || mirror != chosen?.mirror) call.cancel()
            }
            if (!completedNormally && chosen != null) discard(chosen.value)
        }
        synchronized(lock) { winner }
    }

    private fun <T : Any> attempt(
        mirror: GitHubMirror,
        call: Call,
        accept: (GitHubMirror, Call, Response) -> T?
    ): T? {
        val response = try {
            call.execute()
        } catch (_: IOException) {
            return null
        }
        return try {
            accept(mirror, call, response).also { if (it == null) response.close() }
        } catch (_: Exception) {
            response.close()
            null
        }
    }

    // 镜像出错时可能回一大张网页，Release JSON 超过 2 MB 的一律当作无效。
    private fun readCapped(body: ResponseBody): String? {
        if (body.contentLength() > MAX_RELEASE_JSON_BYTES) return null
        val bytes = readUpTo(body.byteStream(), MAX_RELEASE_JSON_BYTES + 1)
        if (bytes.size > MAX_RELEASE_JSON_BYTES) return null
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        while (output.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
