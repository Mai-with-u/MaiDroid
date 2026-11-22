package org.maiwithu.maidroid.pet

import android.content.Context
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/** 桌宠静态页面资源的实际来源，用于在设置/扩展页展示当前是否跟随本机 MaiBot 同步。 */
internal enum class PetAssetSource(val label: String) {
    /** 直接读取容器内 `/opt/MaiBot/src/desktop_pet/web/`，随本机 MaiBot 升级自动同步。 */
    LocalContainer("已跟随本机 MaiBot 同步"),

    /** 容器尚未包含新版桌宠前端时，回退至 APK 内置兜底资源包。 */
    BundledFallback("使用内置兜底版本"),

    /** MaiDroid 自带的 Android 原生通道与旧内核适配脚本。 */
    AndroidBridge("Android 适配层")
}

/** 拦截本机请求后返回给系统 WebView 的完整响应实体。 */
internal class PetWebAsset(
    val mimeType: String,
    val encoding: String?,
    val bytes: ByteArray,
    val source: PetAssetSource,
    val injectCsp: Boolean
)

/**
 * 桌宠前端资产解析器：
 * 1. 对 `/android/...` 返回 APK 内置的聚乙烯兼容层（`polyfill.js`）与 `MessagePort` 桥接脚本；
 * 2. 对 `/pet/...` 优先从本机容器目录 `src/desktop_pet/web/` 读取，若容器缺少桌宠页面或指定文件则透明回退到 APK 内置兜底压缩包；
 * 3. 加载 `.html` 时动态注入 `polyfill.js`、`channel.js`、`adapter.js`、`bridge.js` 与 `mobile.css`，
 *    确保后续本机 MaiBot 升级新增皮肤、音效或交互页面时无需改客户端即可自动同步。
 */
internal class PetAssetResolver(
    private val containerWebDir: File,
    private val openFallbackZip: () -> InputStream?,
    private val openAndroidAsset: (String) -> InputStream?
) {

    companion object {
        private const val FALLBACK_ZIP_ASSET_PATH = "pet/desktop-pet-web-fallback.zip"
        private const val LOCAL_ASSET_ENTRY_MAX_BYTES = 16 * 1024 * 1024

        // 只允许单层或多层子路径的安全字符，拒绝查询串外剩余符号、空段与空白。
        private val allowedRequestPathRegex = Regex("""^/(pet|android)/[A-Za-z0-9_./-]+$""")

        fun from(context: Context, runtimePaths: RuntimePaths): PetAssetResolver {
            val appContext = context.applicationContext
            return PetAssetResolver(
                containerWebDir = File(runtimePaths.maiBotDir, "src/desktop_pet/web"),
                openFallbackZip = {
                    runCatching { appContext.assets.open(FALLBACK_ZIP_ASSET_PATH) }.getOrNull()
                },
                openAndroidAsset = { relPath ->
                    runCatching { appContext.assets.open("pet-android/$relPath") }.getOrNull()
                }
            )
        }
    }

    private val zipCacheLock = Any()

    @Volatile
    private var cachedZipEntries: Map<String, ByteArray>? = null

    /** 容器内的 `src/desktop_pet/web/index.html` 是否就绪，决定是否视为“已跟随本机 MaiBot 同步”。 */
    fun hasLocalContainerDesktopPet(): Boolean =
        File(containerWebDir, "index.html").isFile

    /**
     * 按请求路径解析桌宠或桥接资产；非法路径、路径穿越或缺失资源返回 `null`。
     */
    fun resolve(requestPath: String?): PetWebAsset? {
        if (requestPath.isNullOrBlank()) return null
        if (!allowedRequestPathRegex.matches(requestPath)) return null
        if (requestPath.contains("..") || requestPath.endsWith("/")) return null

        return when {
            requestPath.startsWith("/android/") -> {
                val relPath = requestPath.removePrefix("/android/")
                val raw = readAndClose(openAndroidAsset(relPath)) ?: return null
                buildAsset(relPath, raw, PetAssetSource.AndroidBridge)
            }

            requestPath.startsWith("/pet/") -> {
                val relPath = requestPath.removePrefix("/pet/")
                val localBytes = readFromLocalContainerIfReady(relPath)
                if (localBytes != null) {
                    buildAsset(relPath, localBytes, PetAssetSource.LocalContainer)
                } else {
                    val fallbackBytes = loadFallbackZipEntries()[relPath] ?: return null
                    buildAsset(relPath, fallbackBytes, PetAssetSource.BundledFallback)
                }
            }

            else -> null
        }
    }

    // 仅当容器侧存在主入口 index.html 时才启用本地优先读取，并做规范路径校验防止越界访问容器外文件。
    private fun readFromLocalContainerIfReady(relPath: String): ByteArray? {
        if (!hasLocalContainerDesktopPet()) return null
        return runCatching {
            val rootCanonical = containerWebDir.canonicalFile
            val targetCanonical = File(rootCanonical, relPath).canonicalFile
            if (!targetCanonical.toPath().startsWith(rootCanonical.toPath())) return null
            if (!targetCanonical.isFile || targetCanonical.length() > LOCAL_ASSET_ENTRY_MAX_BYTES) return null
            targetCanonical.readBytes()
        }.getOrNull()
    }

    private fun loadFallbackZipEntries(): Map<String, ByteArray> {
        cachedZipEntries?.let { return it }
        synchronized(zipCacheLock) {
            cachedZipEntries?.let { return it }
            val stream = openFallbackZip() ?: return emptyMap()
            val map = LinkedHashMap<String, ByteArray>()
            runCatching {
                ZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.contains("..")) {
                            val normalized = entry.name.trimStart('/').replace('\\', '/')
                            map[normalized] = readStreamBounded(zip, LOCAL_ASSET_ENTRY_MAX_BYTES)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            cachedZipEntries = map
            return map
        }
    }

    private fun buildAsset(
        relPath: String,
        rawBytes: ByteArray,
        source: PetAssetSource
    ): PetWebAsset? {
        val lower = relPath.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".html") -> {
                val injected = injectHtmlAssets(String(rawBytes, Charsets.UTF_8))
                PetWebAsset(
                    mimeType = "text/html",
                    encoding = "UTF-8",
                    bytes = injected.toByteArray(Charsets.UTF_8),
                    source = source,
                    injectCsp = true
                )
            }

            lower.endsWith(".js") || lower.endsWith(".mjs") ->
                PetWebAsset("application/javascript", "UTF-8", rawBytes, source, injectCsp = true)

            lower.endsWith(".css") ->
                PetWebAsset("text/css", "UTF-8", rawBytes, source, injectCsp = true)

            lower.endsWith(".woff2") ->
                PetWebAsset("font/woff2", null, rawBytes, source, injectCsp = false)

            lower.endsWith(".wav") ->
                PetWebAsset("audio/wav", null, rawBytes, source, injectCsp = false)

            lower.endsWith(".webp") ->
                PetWebAsset("image/webp", null, rawBytes, source, injectCsp = false)

            lower.endsWith(".png") ->
                PetWebAsset("image/png", null, rawBytes, source, injectCsp = false)

            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ->
                PetWebAsset("image/jpeg", null, rawBytes, source, injectCsp = false)

            lower.endsWith(".svg") ->
                PetWebAsset("image/svg+xml", "UTF-8", rawBytes, source, injectCsp = false)

            lower.endsWith(".json") ->
                PetWebAsset("application/json", "UTF-8", rawBytes, source, injectCsp = false)

            lower.endsWith(".txt") ->
                PetWebAsset("text/plain", "UTF-8", rawBytes, source, injectCsp = false)

            else -> null
        }
    }

    /**
     * 在 HTML 文档头部注入运行所需的兼容脚本与移动端样式：
     * - `polyfill.js` 必须排在最前，确保 `Object.hasOwn` / `crypto.randomUUID` / `replaceAll` 在 `host-bridge.js` 前就位；
     * - 未声明 viewport 的页面（如 `index.html`）自动补齐固定比例视口声明。
     */
    internal fun injectHtmlAssets(html: String): String {
        val headScripts = buildString {
            if (!html.contains("name=\"viewport\"", ignoreCase = true) &&
                !html.contains("name='viewport'", ignoreCase = true)
            ) {
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">")
            }
            append("<script src=\"/android/polyfill.js\"></script>")
            append("<script src=\"/android/channel.js\"></script>")
            append("<script src=\"/android/adapter.js\"></script>")
            append("<script src=\"/android/bridge.js\"></script>")
        }
        val withHead = replaceFirstIgnoreCase(html, "<head>", "<head>$headScripts")
        return replaceFirstIgnoreCase(
            withHead,
            "</head>",
            "<link rel=\"stylesheet\" href=\"/android/mobile.css\"></head>"
        )
    }

    private fun replaceFirstIgnoreCase(source: String, target: String, replacement: String): String {
        val index = source.indexOf(target, ignoreCase = true)
        if (index < 0) return source
        return source.substring(0, index) + replacement + source.substring(index + target.length)
    }

    private fun readAndClose(stream: InputStream?): ByteArray? =
        stream?.use { readStreamBounded(it, LOCAL_ASSET_ENTRY_MAX_BYTES) }

    private fun readStreamBounded(input: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw IllegalStateException("桌宠静态资源体积超过限制")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
