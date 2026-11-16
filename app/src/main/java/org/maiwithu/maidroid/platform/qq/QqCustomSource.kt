package org.maiwithu.maidroid.platform.qq

import java.net.URI

/**
 * 用户填写的 QQ 协议端（NapCat 或 SnowLuma）自定义安装包来源。
 * 上游原仓库不可用后，不在应用里写死任何第三方 fork，由用户自己填入仓库地址或安装包直链。
 */
internal sealed interface QqCustomSource {
    val rawUrl: String

    /** 用户直接填写了安装包直链（NapCat 为 .zip，SnowLuma 为 .tar.gz 或 .tgz）。 */
    data class DirectArchive(override val rawUrl: String) : QqCustomSource

    /**
     * 用户填写了代码仓库地址（例如 https://github.com/owner/repo 或具体 tag 页链接）。
     * repoBaseUrl 已去掉尾随斜杠、.git、/releases...、/tree/... 等后缀；
     * pinnedTag 在用户填写 .../releases/tag/<tag> 时记录对应的 tag；
     * gitHubRepoSlug 在域名为 github.com 时形如 "<owner>/<repo>"，供查询 GitHub Releases API。
     */
    data class Repository(
        override val rawUrl: String,
        val repoBaseUrl: String,
        val pinnedTag: String? = null,
        val gitHubRepoSlug: String? = null
    ) : QqCustomSource
}

internal sealed interface QqSourceParseResult {
    data class Valid(val source: QqCustomSource) : QqSourceParseResult
    data class Invalid(val reason: String) : QqSourceParseResult
}

private val REPO_SUFFIX_MARKERS = setOf("releases", "tree", "blob", "commits", "tags")

/**
 * 解析并校验用户输入的 NapCat / SnowLuma 自定义仓库或安装包直链。
 * 先拦截空值、空白字符、带账号密码的链接，再区分安装包直链与代码仓库地址；
 * 对 GitHub 仓库的 releases/tag/<tag> 保留指定的 tag，方便用户锁定特定版本。
 */
internal fun parseQqCustomSource(client: QqClient, input: String): QqSourceParseResult {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return QqSourceParseResult.Invalid("请填写 ${client.label} 的自定义仓库地址或安装包直链")
    }
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) {
        return QqSourceParseResult.Invalid("地址不能包含空格或换行")
    }
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: return QqSourceParseResult.Invalid("请输入合法的 HTTP 或 HTTPS 地址")
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return QqSourceParseResult.Invalid("地址必须以 https:// 或 http:// 开头")
    }
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
        ?: return QqSourceParseResult.Invalid("地址缺少域名")
    if (!uri.userInfo.isNullOrEmpty()) {
        return QqSourceParseResult.Invalid("地址中请勿包含用户名或密码")
    }

    val cleanPath = (uri.path ?: "").trimEnd('/')
    when (client) {
        QqClient.NapCat -> {
            if (cleanPath.endsWith(".zip", ignoreCase = true)) {
                return QqSourceParseResult.Valid(
                    QqCustomSource.DirectArchive(normalizeArchiveUrl(uri, scheme, host))
                )
            }
            if (cleanPath.endsWith(".tar.gz", ignoreCase = true) || cleanPath.endsWith(".tgz", ignoreCase = true)) {
                return QqSourceParseResult.Invalid("NapCat 需要填写仓库地址或 .zip 安装包直链（如 NapCat.Shell.zip）")
            }
        }
        QqClient.SnowLuma -> {
            if (cleanPath.endsWith(".tar.gz", ignoreCase = true) || cleanPath.endsWith(".tgz", ignoreCase = true)) {
                return QqSourceParseResult.Valid(
                    QqCustomSource.DirectArchive(normalizeArchiveUrl(uri, scheme, host))
                )
            }
            if (cleanPath.endsWith(".zip", ignoreCase = true)) {
                return QqSourceParseResult.Invalid("SnowLuma 在容器里需要 linux-arm64 的 .tar.gz 直链或仓库地址，不能使用 .zip 包")
            }
        }
    }

    if (host == "raw.githubusercontent.com" || host == "objects.githubusercontent.com" || host == "release-assets.githubusercontent.com") {
        return QqSourceParseResult.Invalid(directArchiveHint(client))
    }

    val segments = cleanPath.split('/').filter { it.isNotEmpty() }
    val releasesDownloadIdx = segments.indexOf("releases")
    if (releasesDownloadIdx >= 0 && segments.getOrNull(releasesDownloadIdx + 1) == "download") {
        return QqSourceParseResult.Invalid(directArchiveHint(client))
    }
    if (segments.size < 2) {
        return QqSourceParseResult.Invalid("仓库地址不完整，需包含所有者与仓库名（如 https://github.com/owner/repo）")
    }

    val isGitHub = host == "github.com" || host == "www.github.com"
    val suffixIndex = segments.indexOfFirst { it.lowercase() in REPO_SUFFIX_MARKERS }
    val rawRepoSegments: List<String>
    var pinnedTag: String? = null
    if (suffixIndex >= 2) {
        rawRepoSegments = segments.subList(0, suffixIndex)
        if (segments[suffixIndex].equals("releases", ignoreCase = true) &&
            segments.getOrNull(suffixIndex + 1)?.equals("tag", ignoreCase = true) == true &&
            suffixIndex + 2 < segments.size
        ) {
            pinnedTag = segments.subList(suffixIndex + 2, segments.size).joinToString("/")
        }
    } else {
        if (isGitHub && segments.size > 2) {
            return QqSourceParseResult.Invalid("请填写仓库主页地址（如 https://github.com/owner/repo）或安装包直链")
        }
        rawRepoSegments = segments
    }

    val lastSegment = rawRepoSegments.last().removeSuffix(".git")
    if (lastSegment.isBlank()) {
        return QqSourceParseResult.Invalid("仓库地址不完整，需包含所有者与仓库名")
    }
    val repoSegments = rawRepoSegments.dropLast(1) + lastSegment
    val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
    val normalizedHost = if (isGitHub) "github.com" else host
    val repoBaseUrl = "$scheme://$normalizedHost$portSuffix/${repoSegments.joinToString("/")}"
    val gitHubSlug = if (isGitHub && repoSegments.size == 2) "${repoSegments[0]}/${repoSegments[1]}" else null

    return QqSourceParseResult.Valid(
        QqCustomSource.Repository(
            rawUrl = trimmed,
            repoBaseUrl = repoBaseUrl,
            pinnedTag = pinnedTag,
            gitHubRepoSlug = gitHubSlug
        )
    )
}

/**
 * 安装 SnowLuma 前点“用户协议 / 隐私政策”时，按当前填入的仓库地址拼出对应文档链接；
 * 只填了安装包直链或还没填有效仓库时返回 null，界面改用提示告诉用户先填仓库地址。
 */
internal fun snowLumaDocUrl(input: String, docFileName: String): String? {
    val valid = parseQqCustomSource(QqClient.SnowLuma, input) as? QqSourceParseResult.Valid ?: return null
    val repo = valid.source as? QqCustomSource.Repository ?: return null
    val ref = repo.pinnedTag ?: "HEAD"
    return "${repo.repoBaseUrl}/blob/$ref/$docFileName"
}

private fun directArchiveHint(client: QqClient): String = when (client) {
    QqClient.NapCat -> "直链需指向 .zip 安装包（如 NapCat.Shell.zip），或改填仓库主页地址"
    QqClient.SnowLuma -> "直链需指向 linux-arm64 的 .tar.gz 安装包，或改填仓库主页地址"
}

/** 规范化直链协议头小写与 www.github.com 域名，保证 GitHubMirrors 能识别加速候选；保留原始编码路径与查询串。 */
private fun normalizeArchiveUrl(uri: URI, scheme: String, host: String): String {
    val portSuffix = if (uri.port != -1) ":${uri.port}" else ""
    val normalizedHost = if (host == "www.github.com") "github.com" else host
    val path = uri.rawPath.orEmpty()
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
    return "$scheme://$normalizedHost$portSuffix$path$query$fragment"
}
