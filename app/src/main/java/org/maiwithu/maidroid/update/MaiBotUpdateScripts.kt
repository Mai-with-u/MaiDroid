package org.maiwithu.maidroid.update

import org.maiwithu.maidroid.container.MaiBotContainerConfig

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
    val localChangeCount: Int?,
    val backupFile: String?
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
    const val BACKUP = "${PREFIX}BACKUP"
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
        backupFile = values[MaiBotUpdateReportKeys.BACKUP]?.trim()?.ifBlank { null }
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

/**
 * 生成在线更新用到的三段容器命令。
 *
 * OOBE 以 `--depth=1` 经镜像克隆 MaiBot，origin 指向的可能是早已失效的镜像地址，
 * 所以这里不直接 `git pull`，而是每次都按镜像列表重新拉取上游分支到 origin/<分支>，
 * 再把工作区硬重置过去。被 Git 忽略的配置、数据、插件和 .venv 不受影响；
 * 被改过的受版本管理文件会先导出成补丁备份。
 */
internal class MaiBotUpdateScripts(private val config: MaiBotContainerConfig) {
    companion object {
        /** 容器内路径：更新前被改动的受版本管理文件导出为补丁放在这里。 */
        const val BACKUP_DIR = "/root/.maidroid/maibot-backups"
    }

    /** 只读当前安装的版本，不联网，MaiBot 运行中也能执行。 */
    fun installedRevisionCommand(): List<String> = command(
        repositoryPrelude(),
        reportScript(includeLatest = false)
    )

    /** 拉取上游最新提交但不动工作区，MaiBot 运行中也能执行。 */
    fun fetchCommand(): List<String> = command(
        config.githubMirrorScript(),
        repositoryPrelude(),
        fetchScript(),
        reportScript(includeLatest = true)
    )

    /** 拉取 -> 备份本地改动 -> 硬重置到上游。调用前必须先停掉 MaiBot，避免运行中源码被替换。 */
    fun applyCommand(): List<String> = command(
        config.githubMirrorScript(),
        repositoryPrelude(),
        fetchScript(),
        backupAndResetScript(),
        reportScript(includeLatest = true)
    )

    private fun command(vararg parts: String): List<String> =
        config.shellCommand(
            script = parts.joinToString("\n"),
            workingDirectory = MaiBotContainerConfig.MAIBOT_CONTAINER_DIR
        )

    // 进入 MaiBot 仓库并确定要跟随的分支；分离 HEAD 时退回上游默认分支 main。
    private fun repositoryPrelude(): String {
        val installDir = config.shellQuote(MaiBotContainerConfig.MAIBOT_CONTAINER_DIR)
        return """
        set -e
        install_dir=$installDir
        # proot 假 root 下仓库属主可能与当前用户不一致，git 会拒绝操作，这里只对 MaiBot 目录放行。
        export GIT_CONFIG_COUNT=1
        export GIT_CONFIG_KEY_0=safe.directory
        export GIT_CONFIG_VALUE_0="${'$'}install_dir"
        cd "${'$'}install_dir"
        if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
            printf "[MaiBotUpdate] %s is not a git repository, online update is unavailable\n" "${'$'}install_dir"
            exit 3
        fi
        branch="${'$'}(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
        if [ -z "${'$'}branch" ]; then
            branch=main
        fi
        remote_ref="refs/remotes/origin/${'$'}branch"

        maidroid_report_revision() {
            printf "%s=%s\n" "${'$'}1" "${'$'}(git log -1 --format='%H%x09%ct%x09%s' "${'$'}2")"
        }
        """.trimIndent()
    }

    // 浅拉取只下载最新一个提交，手机流量和存储都更友好。
    private fun fetchScript(): String =
        """
        printf "[MaiBotUpdate] fetching %s from upstream\n" "${'$'}branch"
        maidroid_github_fetch ${config.shellQuote(MaiBotContainerConfig.REPOSITORY_URL)} "+refs/heads/${'$'}branch:${'$'}remote_ref" --depth=1 --no-tags
        """.trimIndent()

    private fun backupAndResetScript(): String =
        """
        if [ -n "${'$'}(git status --porcelain --untracked-files=no)" ]; then
            backup_dir=${config.shellQuote(BACKUP_DIR)}
            mkdir -p "${'$'}backup_dir"
            backup_file="${'$'}backup_dir/local-changes-${'$'}(date +%Y%m%d%H%M%S).patch"
            git diff --binary HEAD > "${'$'}backup_file"
            printf "[MaiBotUpdate] local changes saved to %s\n" "${'$'}backup_file"
            printf "%s=%s\n" ${MaiBotUpdateReportKeys.BACKUP} "${'$'}backup_file"
        fi
        printf "[MaiBotUpdate] resetting to %s\n" "${'$'}(git rev-parse --short "${'$'}remote_ref")"
        git reset --hard "${'$'}remote_ref"
        """.trimIndent()

    private fun reportScript(includeLatest: Boolean): String {
        val latestLine = if (includeLatest) {
            "maidroid_report_revision ${MaiBotUpdateReportKeys.LATEST} \"${'$'}remote_ref\""
        } else {
            ""
        }
        return """
        printf "%s=%s\n" ${MaiBotUpdateReportKeys.BRANCH} "${'$'}branch"
        maidroid_report_revision ${MaiBotUpdateReportKeys.INSTALLED} HEAD
        $latestLine
        printf "%s=%s\n" ${MaiBotUpdateReportKeys.LOCAL_CHANGES} "${'$'}(git status --porcelain --untracked-files=no | wc -l | tr -d ' ')"
        """.trimIndent()
    }
}
