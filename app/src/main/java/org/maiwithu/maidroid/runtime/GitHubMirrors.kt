package org.maiwithu.maidroid.runtime

import java.net.URI

/** 一个 GitHub 下载线路：显示名和地址前缀，前缀为空表示直连 GitHub；实际地址 = 前缀 + "/" + 原始地址。 */
internal data class GitHubMirror(val name: String, val prefix: String) {
    val isDirect: Boolean get() = prefix.isEmpty()

    fun url(originalUrl: String): String =
        if (isDirect) originalUrl else "${prefix.trimEnd('/')}/$originalUrl"
}

/**
 * 国内访问 GitHub 不稳定，容器里所有 git clone / git fetch 都经这里的镜像列表依次重试；
 * 宿主侧的应用自更新（appupdate）也用同一份列表，查版本和下载安装包时同时试所有线路，用最快的那个。
 *
 * 容器脚本的用法：把 shellFunctions() 拼在脚本最前面，之后脚本里调用
 * `maidroid_github_clone <仓库地址> <目标目录> [git clone 参数...]` 或
 * `maidroid_github_fetch <仓库地址> <refspec> [git fetch 参数...]`。
 */
internal object GitHubMirrors {
    /** 每个镜像连续尝试的次数，全部失败才换下一个镜像。 */
    const val RETRY_PER_MIRROR = 3

    /** 直连 GitHub（不经镜像）这条线路在界面和日志里的显示名，容器脚本和宿主侧共用。 */
    const val DIRECT_NAME = "直连"

    // git 默认不检测传输停滞：镜像连上后卡住不动，clone/fetch 会一直等下去，OOBE 就停在“正在克隆”出不来。
    // 连续 60 秒低于 1 KB/s 就判这一次失败，按下面的流程重试或换下一个镜像。
    private const val GIT_STALL_OPTIONS = "-c http.lowSpeedLimit=1000 -c http.lowSpeedTime=60"

    // 容器脚本按这个顺序依次重试。ghfast.top 是 NapCat 官方安装脚本也在用的线路，
    // 能代理 git clone 和 Release 下载，但不代理 api.github.com（实测 403），放在最后兜底。
    // 前缀为空的那一项是直连：原样访问原始地址（github.com、api.github.com 等），不经任何镜像。
    val ALL: List<GitHubMirror> = listOf(
        GitHubMirror("gitproxy.mrhjx.cn", "https://gitproxy.mrhjx.cn"),
        GitHubMirror("ghproxy.vip", "https://ghproxy.vip"),
        GitHubMirror(DIRECT_NAME, ""),
        GitHubMirror("gh-proxy.com", "https://gh-proxy.com"),
        GitHubMirror("v6.gh-proxy.org", "https://v6.gh-proxy.org"),
        GitHubMirror("cdn.gh-proxy.com", "https://cdn.gh-proxy.com"),
        GitHubMirror("ghfast.top", "https://ghfast.top")
    )

    // 镜像只代理这几个 GitHub 域名；其它地址（例如本地调试用的假 Release 源）只能直连。
    private val MIRRORED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com"
    )

    // shell 函数里每项是“显示名|地址前缀”，前缀为空表示直连。
    private val MIRROR_SPECS = ALL.map { "${it.name}|${it.prefix}" }

    /** 宿主侧下载一个地址时可以试的线路：GitHub 的地址返回直连加全部镜像，其它地址只有直连。 */
    fun candidatesFor(url: String): List<GitHubMirror> {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == null || host !in MIRRORED_HOSTS) return ALL.filter { it.isDirect }
        return ALL
    }

    /** 定义 maidroid_github_clone、maidroid_github_fetch 两个 shell 函数（及其内部辅助函数）。 */
    fun shellFunctions(): String {
        val mirrorSpecArguments = MIRROR_SPECS.joinToString(" \\\n                ") {
            shellQuote(it)
        }
        return """
        maidroid_github_mirror_specs() {
            printf '%s\n' \
                $mirrorSpecArguments
        }

        maidroid_github_candidate_url() {
            mirror_prefix="${'$'}1"
            original_url="${'$'}2"
            if [ -n "${'$'}mirror_prefix" ]; then
                printf "%s/%s\n" "${'$'}{mirror_prefix%/}" "${'$'}original_url"
            else
                printf "%s\n" "${'$'}original_url"
            fi
        }

        maidroid_github_clone() {
            repo_url="${'$'}1"
            clone_dest="${'$'}2"
            shift 2
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                candidate_url="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" "${'$'}repo_url")"
                attempt=1
                while [ "${'$'}attempt" -le $RETRY_PER_MIRROR ]; do
                    rm -rf "${'$'}clone_dest"
                    printf "[GitHub] git clone via %s (attempt %s/$RETRY_PER_MIRROR): %s\n" "${'$'}mirror_name" "${'$'}attempt" "${'$'}candidate_url"
                    if git $GIT_STALL_OPTIONS clone "${'$'}@" "${'$'}candidate_url" "${'$'}clone_dest"; then
                        printf "[GitHub] git clone succeeded via %s\n" "${'$'}mirror_name"
                        return 0
                    fi
                    rm -rf "${'$'}clone_dest"
                    attempt=${'$'}((attempt + 1))
                    sleep 1
                done
                printf "[GitHub] git clone failed via %s, switching mirror\n" "${'$'}mirror_name"
            done
            printf "[GitHub] git clone failed after all mirrors: %s\n" "${'$'}repo_url"
            return 1
        }

        maidroid_github_fetch() {
            repo_url="${'$'}1"
            fetch_refspec="${'$'}2"
            shift 2
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                candidate_url="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" "${'$'}repo_url")"
                attempt=1
                while [ "${'$'}attempt" -le $RETRY_PER_MIRROR ]; do
                    printf "[GitHub] git fetch via %s (attempt %s/$RETRY_PER_MIRROR): %s\n" "${'$'}mirror_name" "${'$'}attempt" "${'$'}candidate_url"
                    if git $GIT_STALL_OPTIONS fetch "${'$'}@" "${'$'}candidate_url" "${'$'}fetch_refspec"; then
                        printf "[GitHub] git fetch succeeded via %s\n" "${'$'}mirror_name"
                        return 0
                    fi
                    attempt=${'$'}((attempt + 1))
                    sleep 1
                done
                printf "[GitHub] git fetch failed via %s, switching mirror\n" "${'$'}mirror_name"
            done
            printf "[GitHub] git fetch failed after all mirrors: %s\n" "${'$'}repo_url"
            return 1
        }
        """.trimIndent()
    }
}
