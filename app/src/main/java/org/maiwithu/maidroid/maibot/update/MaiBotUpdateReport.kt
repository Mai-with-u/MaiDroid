package org.maiwithu.maidroid.maibot.update

// 在线更新脚本与 Kotlin 之间的结果约定和解析：脚本把版本信息按 KEY=VALUE 打印到输出里，
// 这里从完整输出中挑出这些行，其余行照常写进终端日志。

/** 容器里 MaiBot 仓库的某个提交。 */
internal data class MaiBotRevision(
    val commit: String,
    val committedAtEpochSeconds: Long,
    val subject: String
) {
    val shortCommit: String get() = commit.take(7)
}

/** 更新脚本打印的结果，未打印的字段为空。 */
internal data class MaiBotUpdateReport(
    val branch: String?,
    val installed: MaiBotRevision?,
    val latest: MaiBotRevision?,
    val localChangeCount: Int?
) {
    val hasUpdate: Boolean
        get() = installed != null && latest != null && installed.commit != latest.commit
}

/** 脚本与 Kotlin 之间的结果约定：脚本按 KEY=VALUE 每行打印一项，其余输出照常进终端日志。 */
internal object MaiBotUpdateReportKeys {
    const val PREFIX = "MAIDROID_UPDATE_"
    const val BRANCH = "${PREFIX}BRANCH"
    const val INSTALLED = "${PREFIX}INSTALLED"
    const val LATEST = "${PREFIX}LATEST"
    const val LOCAL_CHANGES = "${PREFIX}LOCAL_CHANGES"
}

/** 同一个 key 出现多次时以最后一次为准：应用更新时脚本会在重置后再打印一次当前版本。 */
internal fun parseMaiBotUpdateReport(output: String): MaiBotUpdateReport {
    val values = mutableMapOf<String, String>()
    output.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.startsWith(MaiBotUpdateReportKeys.PREFIX) && '=' in it }
        .forEach { line -> values[line.substringBefore('=')] = line.substringAfter('=') }

    return MaiBotUpdateReport(
        branch = values[MaiBotUpdateReportKeys.BRANCH]?.trim()?.ifBlank { null },
        installed = values[MaiBotUpdateReportKeys.INSTALLED]?.let(::parseMaiBotRevision),
        latest = values[MaiBotUpdateReportKeys.LATEST]?.let(::parseMaiBotRevision),
        localChangeCount = values[MaiBotUpdateReportKeys.LOCAL_CHANGES]?.trim()?.toIntOrNull()
    )
}

/** 解析 `git log --format=%H%x09%ct%x09%s` 的一行；提交说明里本身不会出现制表符。 */
internal fun parseMaiBotRevision(value: String): MaiBotRevision? {
    val parts = value.split('\t', limit = 3)
    if (parts.size < 2) return null
    val commit = parts[0].trim().lowercase()
    if (commit.length < 7 || !commit.all { it in '0'..'9' || it in 'a'..'f' }) return null
    val committedAt = parts[1].trim().toLongOrNull() ?: return null
    return MaiBotRevision(
        commit = commit,
        committedAtEpochSeconds = committedAt,
        subject = parts.getOrElse(2) { "" }.trim()
    )
}
