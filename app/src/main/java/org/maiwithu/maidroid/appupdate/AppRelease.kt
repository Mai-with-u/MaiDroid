package org.maiwithu.maidroid.appupdate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * 应用版本号：a.b.c 数字段，后面可以带 `-rc1` 这类预发布后缀，与 Release 标签（去掉开头的 v）一一对应。
 * 比较规则与语义化版本一致：数字段逐位比较，缺的位按 0；数字相同时带后缀的更旧（0.2.0-rc1 < 0.2.0）。
 */
internal data class AppVersion(
    val numbers: List<Int>,
    val preRelease: String? = null
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val length = maxOf(numbers.size, other.numbers.size)
        for (index in 0 until length) {
            val diff = numbers.getOrElse(index) { 0 }.compareTo(other.numbers.getOrElse(index) { 0 })
            if (diff != 0) return diff
        }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        numbers.joinToString(".") + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val CORE = Regex("""\d+(\.\d+)*""")
        private val PRE_RELEASE = Regex("""[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*""")

        /** 解析 "v0.1.3"、"0.2.0-rc1" 这类写法；构建元数据（+ 之后）忽略；格式不对返回 null。 */
        fun parse(text: String): AppVersion? {
            val trimmed = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val core = trimmed.substringBefore('-')
            // 有连字符就必须跟着合法的后缀，"1.2.3-" 这种不算版本号
            val preRelease = if ('-' in trimmed) trimmed.substringAfter('-') else null
            if (!CORE.matches(core)) return null
            if (preRelease != null && !PRE_RELEASE.matches(preRelease)) return null
            val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(numbers, preRelease)
        }

        // 没有后缀的是正式版，比任何同号的预发布都新；两边都有后缀时按点分段比较，纯数字段按数值比。
        private fun comparePreRelease(left: String?, right: String?): Int {
            if (left == right) return 0
            if (left == null) return 1
            if (right == null) return -1
            val leftParts = left.split('.')
            val rightParts = right.split('.')
            for (index in 0 until minOf(leftParts.size, rightParts.size)) {
                val a = leftParts[index]
                val b = rightParts[index]
                val aNumber = a.toLongOrNull()
                val bNumber = b.toLongOrNull()
                val diff = when {
                    aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                    aNumber != null -> -1
                    bNumber != null -> 1
                    else -> a.compareTo(b)
                }
                if (diff != 0) return diff
            }
            return leftParts.size.compareTo(rightParts.size)
        }
    }
}

/** Release 里的一个下载资源。size 取自 GitHub 接口，未知时为 0。 */
internal data class AppReleaseAsset(
    val name: String,
    val size: Long,
    val downloadUrl: String
)

/**
 * GitHub 最新正式版 Release 里检查更新要用的信息。
 *
 * notes 是 CHANGELOG.md 对应的那一节，已经去掉发布流程追加的校验尾巴；
 * apkSha256、certSha256 取自那条尾巴，老版本或手工发布的 Release 没有时为 null。
 * apk 为 null 表示这个 Release 里没有能装的 APK。
 */
internal data class AppRelease(
    val tag: String,
    val version: AppVersion,
    val pageUrl: String,
    val publishedAtEpochSeconds: Long?,
    val notes: String,
    val apk: AppReleaseAsset?,
    val apkSha256: String?,
    val certSha256: String?
)

private val releaseJson = Json { ignoreUnknownKeys = true }

/**
 * 解析 GitHub `releases/latest` 接口的返回。镜像出错时可能回一张 HTML 错误页或别的 JSON，
 * 这类内容（不是 JSON、没有标签、标签不是版本号、是草稿或预发布）一律返回 null，交给下一条线路。
 */
internal fun parseAppRelease(text: String): AppRelease? {
    val root = runCatching { releaseJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val tag = root.string("tag_name") ?: return null
    val version = AppVersion.parse(tag) ?: return null
    if (root.boolean("draft") == true || root.boolean("prerelease") == true) return null

    val assets = runCatching {
        root["assets"]?.jsonArray?.mapNotNull { element ->
            val asset = element.jsonObject
            val name = asset.string("name") ?: return@mapNotNull null
            val url = asset.string("browser_download_url") ?: return@mapNotNull null
            AppReleaseAsset(name = name, size = asset.long("size") ?: 0L, downloadUrl = url)
        }
    }.getOrNull().orEmpty()

    val body = root.string("body").orEmpty()
    val apk = pickApkAsset(tag, assets)
    val checksums = parseReleaseChecksums(body)
    return AppRelease(
        tag = tag,
        version = version,
        pageUrl = root.string("html_url").orEmpty(),
        publishedAtEpochSeconds = root.string("published_at")
            ?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
        notes = releaseNotesWithoutChecksums(body),
        apk = apk,
        // 尾巴里写的文件名和挑中的资源对得上才用它的 SHA-256，避免拿别的文件的校验值去比
        apkSha256 = checksums.apkSha256?.takeIf { apk != null && checksums.apkName == apk.name },
        certSha256 = checksums.certSha256
    )
}

/**
 * 挑出要下载的 APK：先找发布流程固定产出的 `MaiDroid_<标签>.apk`；
 * 没有时，如果 Release 里只有一个 .apk 就用它；有多个又对不上名字时不猜，返回 null。
 */
internal fun pickApkAsset(tag: String, assets: List<AppReleaseAsset>): AppReleaseAsset? {
    assets.firstOrNull { it.name == "MaiDroid_$tag.apk" }?.let { return it }
    return assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull()
}

/** 发布流程追加在 Release 说明末尾的校验信息。 */
internal data class ReleaseChecksums(
    val apkName: String? = null,
    val apkSha256: String? = null,
    val certSha256: String? = null
)

private val APK_CHECKSUM = Regex("""`([^`]+)`\s*的\s*SHA-256\s*为\s*`([0-9a-fA-F]{64})`""")
private val CERT_CHECKSUM = Regex("""签名证书\s*SHA-256\s*为\s*`([0-9a-fA-F]{64})`""")
private const val CHECKSUM_LINE_PREFIX = "校验："

/**
 * 读发布流程写的那行“校验：`MaiDroid_vX.apk` 的 SHA-256 为 `…`，签名证书 SHA-256 为 `…`。”
 * 只认最后一行以“校验：”开头的内容，正文里引用的同样格式不算。
 */
internal fun parseReleaseChecksums(body: String): ReleaseChecksums {
    val line = body.lines().lastOrNull { it.trim().startsWith(CHECKSUM_LINE_PREFIX) } ?: return ReleaseChecksums()
    val apk = APK_CHECKSUM.find(line)
    return ReleaseChecksums(
        apkName = apk?.groupValues?.get(1),
        apkSha256 = apk?.groupValues?.get(2)?.lowercase(),
        certSha256 = CERT_CHECKSUM.find(line)?.groupValues?.get(1)?.lowercase()
    )
}

/** 去掉 Release 说明末尾的“---”分隔线和校验行，只留 CHANGELOG 那一节的正文。 */
internal fun releaseNotesWithoutChecksums(body: String): String {
    val lines = body.replace("\r\n", "\n").replace('\r', '\n').lines()
    val checksumIndex = lines.indexOfLast { it.trim().startsWith(CHECKSUM_LINE_PREFIX) }
    if (checksumIndex < 0) return lines.joinToString("\n").trim()
    var cut = checksumIndex
    // 校验行前面紧挨着的空行和“---”分隔线一起去掉
    while (cut > 0 && lines[cut - 1].isBlank()) cut--
    if (cut > 0 && lines[cut - 1].trim() == "---") cut--
    return lines.subList(0, cut).joinToString("\n").trim()
}

/** 更新说明里的一行，界面按种类排版：小标题加粗、列表项带圆点和缩进。 */
internal data class ReleaseNoteLine(
    val kind: ReleaseNoteKind,
    val text: String,
    val indent: Int = 0
)

internal enum class ReleaseNoteKind {
    Heading,
    Bullet,
    Text
}

private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val MARKDOWN_HEADING = Regex("""^#{1,6}\s+(.*)$""")
private val MARKDOWN_BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")

/**
 * 把 CHANGELOG 的 Markdown 拆成界面能直接排版的行：`### 新增` 变小标题，`- ` 变列表项，
 * 行内的链接只留文字，去掉加粗和代码的标记；空行和分隔线跳过。
 */
internal fun releaseNoteLines(notes: String): List<ReleaseNoteLine> =
    notes.replace("\r\n", "\n").lines().mapNotNull { raw ->
        val line = raw.trimEnd()
        if (line.isBlank() || line.trim() == "---") return@mapNotNull null
        MARKDOWN_HEADING.matchEntire(line.trim())?.let {
            return@mapNotNull ReleaseNoteLine(ReleaseNoteKind.Heading, stripInlineMarkdown(it.groupValues[1]))
        }
        MARKDOWN_BULLET.matchEntire(line)?.let {
            // 每两个空格算一级缩进，CHANGELOG 里最多用到两级
            val indent = (it.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(2)
            return@mapNotNull ReleaseNoteLine(ReleaseNoteKind.Bullet, stripInlineMarkdown(it.groupValues[2]), indent)
        }
        ReleaseNoteLine(ReleaseNoteKind.Text, stripInlineMarkdown(line.trim()))
    }

private fun stripInlineMarkdown(text: String): String =
    text.replace(MARKDOWN_LINK, "$1")
        .replace("**", "")
        .replace("`", "")

/**
 * 判断一次断点续传请求的响应能不能接在已下载的部分后面，返回响应体对应的文件起始位置，不能用返回 null。
 *
 * - 206：Content-Range 的起点必须正是请求的位置，总长和 Release 里的大小一致（大小未知时不比）；
 * - 200：服务器不支持断点续传，整个文件从头发，返回 0，调用方要清掉已下载的部分重写；
 *   但 text/ 开头的类型是镜像的错误页，长度和 Release 不符也不是这个文件，都不用；
 * - 其它状态码（含 416）都不能用。
 */
internal fun rangeResponseStart(
    code: Int,
    contentRange: String?,
    contentLength: Long,
    contentType: String?,
    requestedStart: Long,
    expectedTotal: Long
): Long? {
    if (contentType?.trim()?.startsWith("text/", ignoreCase = true) == true) return null
    return when (code) {
        206 -> {
            val match = CONTENT_RANGE.matchEntire(contentRange?.trim().orEmpty()) ?: return null
            val start = match.groupValues[1].toLong()
            val total = match.groupValues[3].toLongOrNull()
            when {
                start != requestedStart -> null
                expectedTotal > 0 && total != null && total != expectedTotal -> null
                else -> start
            }
        }
        200 -> if (expectedTotal > 0 && contentLength >= 0 && contentLength != expectedTotal) null else 0L
        else -> null
    }
}

private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

private fun JsonObject.string(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun JsonObject.long(key: String): Long? =
    runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

private fun JsonObject.boolean(key: String): Boolean? =
    runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
