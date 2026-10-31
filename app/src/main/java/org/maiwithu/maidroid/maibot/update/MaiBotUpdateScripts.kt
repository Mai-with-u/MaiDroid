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
 * 被改过的受版本管理文件直接还原为上游版本，不做备份：MaiDroid 不支持在手机上改 MaiBot 源码，
 * 用户在“文件”应用里误改的源码随更新自动恢复。
 */
internal object MaiBotUpdateScripts {
    // 统计“用户改过的源码”时排除 uv.lock：容器里 uv sync 会把它的源地址改写成国内镜像，
    // 这是 MaiDroid 自己改的，不算用户改动；重置时它照样还原，随后重装依赖时再次改写。
    private const val USER_CHANGES_PATHSPEC = "-- . ':(exclude)uv.lock'"

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

    /** 拉取 -> 丢弃本地改动并硬重置到上游。调用前必须先停掉 MaiBot，避免运行中源码被替换。 */
    fun fetchAndReset(): ContainerScript = script(
        GitHubMirrors.shellFunctions(),
        repositoryPrelude(),
        fetchScript(),
        resetScript(),
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

    // 用户改过的源码文件先在终端日志里列一遍（知道还原了什么），再硬重置到上游；被忽略的文件不受影响。
    private fun resetScript(): String =
        """
        changed="${'$'}(git status --porcelain --untracked-files=no $USER_CHANGES_PATHSPEC)"
        if [ -n "${'$'}changed" ]; then
            printf "[MaiBotUpdate] discarding local changes to tracked files:\n%s\n" "${'$'}changed"
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
        printf "%s=%s\n" ${MaiBotUpdateReportKeys.LOCAL_CHANGES} "${'$'}(git status --porcelain --untracked-files=no $USER_CHANGES_PATHSPEC | wc -l | tr -d ' ')"
        """.trimIndent()
    }
}
