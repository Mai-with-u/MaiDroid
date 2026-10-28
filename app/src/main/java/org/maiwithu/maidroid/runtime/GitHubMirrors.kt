package org.maiwithu.maidroid.runtime

/**
 * 国内访问 GitHub 不稳定，容器里所有 git clone / git fetch 都经这里的镜像列表依次重试。
 *
 * 用法：把 shellFunctions() 拼在脚本最前面，之后脚本里调用
 * `maidroid_github_clone <仓库地址> <目标目录> [git clone 参数...]` 或
 * `maidroid_github_fetch <仓库地址> <refspec> [git fetch 参数...]`。
 */
internal object GitHubMirrors {
    /** 每个镜像连续尝试的次数，全部失败才换下一个镜像。 */
    const val RETRY_PER_MIRROR = 3

    // 每项是“显示名|地址前缀”，前缀为空表示直连 GitHub；实际地址 = 前缀 + "/" + 原始地址。
    private val MIRROR_SPECS = listOf(
        "gitproxy.mrhjx.cn|https://gitproxy.mrhjx.cn",
        "ghproxy.vip|https://ghproxy.vip",
        "raw.githubusercontent.com|",
        "gh-proxy.com|https://gh-proxy.com",
        "v6.gh-proxy.org|https://v6.gh-proxy.org",
        "cdn.gh-proxy.com|https://cdn.gh-proxy.com"
    )

    /** OOBE 卡片上展示的镜像说明，例如“GitHub 镜像自动重试（每源 3 次）: a -> b -> c”。 */
    val DESCRIPTION: String =
        "GitHub 镜像自动重试（每源 ${RETRY_PER_MIRROR} 次）: " +
            MIRROR_SPECS.joinToString(" -> ") { it.substringBefore('|') }

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
                    if git clone "${'$'}@" "${'$'}candidate_url" "${'$'}clone_dest"; then
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
                    if git fetch "${'$'}@" "${'$'}candidate_url" "${'$'}fetch_refspec"; then
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
