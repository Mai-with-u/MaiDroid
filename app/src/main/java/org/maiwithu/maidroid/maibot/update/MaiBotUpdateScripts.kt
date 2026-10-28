package org.maiwithu.maidroid.maibot.update

import org.maiwithu.maidroid.maibot.MaiBotScripts
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * 生成在线更新用到的三段容器命令。
 *
 * OOBE 以 `--depth=1` 经镜像克隆 MaiBot，origin 指向的可能是早已失效的镜像地址，
 * 所以这里不直接 `git pull`，而是每次都按镜像列表重新拉取上游分支到 origin/<分支>，
 * 再把工作区硬重置过去。被 Git 忽略的配置、数据、插件和 .venv 不受影响；
 * 被改过的受版本管理文件会先导出成补丁备份。
 */
internal object MaiBotUpdateScripts {
    /** 容器内路径：更新前被改动的受版本管理文件导出为补丁放在这里。 */
    const val BACKUP_DIR = "/root/.maidroid/maibot-backups"

    /** 只读当前安装的版本，不联网，MaiBot 运行中也能执行。 */
    fun installedRevision(): ContainerScript = script(
        repositoryPrelude(),
        reportScript(includeLatest = false)
    )

    /** 拉取上游最新提交但不动工作区，MaiBot 运行中也能执行。 */
    fun fetchLatest(): ContainerScript = script(
        GitHubMirrors.shellFunctions(),
        repositoryPrelude(),
        fetchScript(),
        reportScript(includeLatest = true)
    )

    /** 拉取 -> 备份本地改动 -> 硬重置到上游。调用前必须先停掉 MaiBot，避免运行中源码被替换。 */
    fun fetchAndReset(): ContainerScript = script(
        GitHubMirrors.shellFunctions(),
        repositoryPrelude(),
        fetchScript(),
        backupAndResetScript(),
        reportScript(includeLatest = true)
    )

    private fun script(vararg parts: String): ContainerScript =
        ContainerScript(
            body = parts.joinToString("\n"),
            workingDirectory = InContainerPaths.MAIBOT_DIR
        )

    // 进入 MaiBot 仓库并确定要跟随的分支；分离 HEAD 时退回上游默认分支 main。
    private fun repositoryPrelude(): String {
        val installDir = shellQuote(InContainerPaths.MAIBOT_DIR)
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
        maidroid_github_fetch ${shellQuote(MaiBotScripts.REPOSITORY_URL)} "+refs/heads/${'$'}branch:${'$'}remote_ref" --depth=1 --no-tags
        """.trimIndent()

    private fun backupAndResetScript(): String =
        """
        if [ -n "${'$'}(git status --porcelain --untracked-files=no)" ]; then
            backup_dir=${shellQuote(BACKUP_DIR)}
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
