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

/**
 * 更新脚本打印的结果，未打印的字段为空。
 * 两个数据库结构版本：databaseSchemaVersion 是麦麦数据库现在的版本（SQLite 的 user_version），
 * targetSchemaVersion 是拉下来的上游代码最高支持到的版本；读不到的为空，不据此拦截。
 */
internal data class MaiBotUpdateReport(
    val branch: String?,
    val installed: MaiBotRevision?,
    val latest: MaiBotRevision?,
    val localChangeCount: Int?,
    val targetSchemaVersion: Int? = null,
    val databaseSchemaVersion: Int? = null
) {
    val hasUpdate: Boolean
        get() = installed != null && latest != null && installed.commit != latest.commit

    /**
     * 换到上游代码会让麦麦起不来：MaiBot 的数据库迁移只能升级，库的版本比代码支持的新时启动直接报错退出
     * （上游 src/common/database/migrations/bootstrap.py）。从测试版切回正式版、上游 dev 回退提交时都可能遇到。
     */
    val wouldDowngradeDatabase: Boolean
        get() = targetSchemaVersion != null && databaseSchemaVersion != null &&
            databaseSchemaVersion > targetSchemaVersion
}

/** 脚本与 Kotlin 之间的结果约定：脚本按 KEY=VALUE 每行打印一项，其余输出照常进终端日志。 */
internal object MaiBotUpdateReportKeys {
    const val PREFIX = "MAIDROID_UPDATE_"
    const val BRANCH = "${PREFIX}BRANCH"
    const val INSTALLED = "${PREFIX}INSTALLED"
    const val LATEST = "${PREFIX}LATEST"
    const val LOCAL_CHANGES = "${PREFIX}LOCAL_CHANGES"
    const val TARGET_SCHEMA = "${PREFIX}TARGET_SCHEMA"
    const val DATABASE_SCHEMA = "${PREFIX}DATABASE_SCHEMA"
}

/**
 * 换过去会让数据库降级时给用户看的原因，不会降级时返回 null。设置页那一行会在前面加“切换失败：”“更新失败：”，这里不再带前缀。
 * target 是要切换到的通道；为空表示更新当前分支，这时只可能是上游回退了提交。
 */
internal fun maiBotDatabaseDowngradeMessage(report: MaiBotUpdateReport, target: MaiBotChannel?): String? {
    if (!report.wouldDowngradeDatabase) return null
    val versions = "麦麦的数据库已是第 ${report.databaseSchemaVersion} 版，" +
        "${target?.label ?: "上游最新版本"}只支持到第 ${report.targetSchemaVersion} 版，MaiBot 不支持数据库降级。"
    return if (target != null) {
        versions + "切过去麦麦会启动失败，已取消切换，等${target.label}跟上后再切。"
    } else {
        versions + "上游可能回退了提交，更新后麦麦会启动失败，暂不更新，等上游跟上后再更新。"
    }
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
        localChangeCount = values[MaiBotUpdateReportKeys.LOCAL_CHANGES]?.trim()?.toIntOrNull(),
        targetSchemaVersion = values[MaiBotUpdateReportKeys.TARGET_SCHEMA]?.trim()?.toIntOrNull(),
        databaseSchemaVersion = values[MaiBotUpdateReportKeys.DATABASE_SCHEMA]?.trim()?.toIntOrNull()
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
