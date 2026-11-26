package org.maiwithu.maidroid.maibot

import org.maiwithu.maidroid.diagnostics.AUTO_FIX_LOG_PREFIX
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ExitTarget
import org.maiwithu.maidroid.runtime.GitHubMirror
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.PyPiMirror
import org.maiwithu.maidroid.runtime.PyPiMirrors
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.requestExitScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * MaiBot 本体在容器里的安装与运行脚本，对应上游 installation.md 的手动步骤：
 * 克隆源码 -> 安装 uv -> 准备 Python 解释器 -> uv sync 装依赖 -> uv run python bot.py 启动。
 */
internal object MaiBotScripts {
    const val REPOSITORY_URL = "https://github.com/Mai-with-u/MaiBot.git"

    // MaiBot 的 requires-python 只写了 >=3.12，没有上限也没有 .python-version，uv 会自动选最新的 Python。
    // 新版 Python 刚发布时，pydantic-core、pyarrow、zstandard 等包在 aarch64 上还没有预编译包，
    // uv 只能退回源码编译，而容器里没有 C 编译器和 Rust，uv sync 会直接失败。
    // 这里把范围压在 3.12~3.13：全新安装时 uv 选其中最新的 3.13（与上游 Dockerfile 一致），
    // 已经装好 3.12 的旧环境也不会被重建。
    const val UV_PYTHON_REQUEST = ">=3.12,<3.14"

    // 停止时等 Runner 退出的上限：Runner 最多等 Worker 关闭 60 秒，超时强杀 Worker 后再等 5 秒回收
    // （MaiBot 的 WORKER_SHUTDOWN_TIMEOUT），所以 Runner 最晚约 65 秒退出，这里再留一些余量。
    private const val EXIT_TIMEOUT_SECONDS = 80

    private const val MAIBOT_DIR = InContainerPaths.MAIBOT_DIR

    // uv 官方安装脚本（会跳转到 releases.astral.sh），以及 GitHub 发布页上的同一份，后者可以经镜像下载。
    private const val UV_INSTALLER_URL = "https://astral.sh/uv/install.sh"
    private const val UV_INSTALLER_GITHUB_URL =
        "https://github.com/astral-sh/uv/releases/latest/download/uv-installer.sh"

    // uv 下载 Python 时 UV_PYTHON_INSTALL_MIRROR 替换的就是这个前缀。
    private const val PYTHON_DOWNLOAD_GITHUB_URL =
        "https://github.com/astral-sh/python-build-standalone/releases/download"

    // 克隆 -> 改名 -> 立刻读一次 HEAD 确认仓库可用。
    // proot 的 --link2symlink 会把 git 落盘 packfile 用的硬链接改成指向绝对路径的符号链接，
    // 先克隆到 MaiBot.tmp 再改名后，这些链接还指着旧路径，git 就读不到对象（fatal: bad object HEAD），
    // 在线更新和 MaiBot 自带的更新检查都会失败。core.createObject=rename 让 git 直接用重命名落盘，
    // 不产生链接；该配置会写进仓库的 .git/config，之后容器里任何 git fetch 也沿用。
    fun clone(gitHubMirrors: List<GitHubMirror> = GitHubMirrors.ALL): ContainerScript = ContainerScript(
        listOf(
            GitHubMirrors.shellFunctions(gitHubMirrors),
            """
            set -e
            mkdir -p /opt
            rm -rf ${shellQuote("$MAIBOT_DIR.tmp")}
            maidroid_github_clone ${shellQuote(REPOSITORY_URL)} ${shellQuote("$MAIBOT_DIR.tmp")} -c core.createObject=rename --depth=1 --single-branch
            rm -rf ${shellQuote(MAIBOT_DIR)}
            mv ${shellQuote("$MAIBOT_DIR.tmp")} ${shellQuote(MAIBOT_DIR)}
            if ! git -C ${shellQuote(MAIBOT_DIR)} cat-file -e 'HEAD^{commit}'; then
                printf "[GitHub] cloned repository is unreadable after move: %s\n" ${shellQuote(MAIBOT_DIR)}
                exit 1
            fi
            """.trimIndent()
        ).joinToString("\n")
    )

    /**
     * 用 uv 官方安装脚本把 uv 装到 /root/.local/bin；已有能运行的 uv 就直接跳过。
     * 按 [gitHubMirrors] 顺序决定的首选策略下载脚本与 binary：
     * - 直连排首位时优先 astral.sh / 官方源，失败再试镜像；
     * - 加速镜像排首位时先按测速或用户选定的代理顺序安装，全部失败再兜底 official，避免卡等官方超时。
     */
    fun installUv(gitHubMirrors: List<GitHubMirror> = GitHubMirrors.ALL): ContainerScript {
        val ordered = gitHubMirrors.ifEmpty { GitHubMirrors.ALL }
        val preferDirect = ordered.first().isDirect
        val fetchScriptBlock = if (preferDirect) {
            """
            if maidroid_fetch_uv_installer ${shellQuote(UV_INSTALLER_URL)}; then
                installer_ready=1
            else
                for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                    mirror_prefix="${'$'}{mirror_spec#*|}"
                    if maidroid_fetch_uv_installer "${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" ${shellQuote(UV_INSTALLER_GITHUB_URL)})"; then
                        installer_ready=1
                        break
                    fi
                done
            fi
            """.trimIndent()
        } else {
            """
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_prefix="${'$'}{mirror_spec#*|}"
                if maidroid_fetch_uv_installer "${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" ${shellQuote(UV_INSTALLER_GITHUB_URL)})"; then
                    installer_ready=1
                    break
                fi
            done
            if [ "${'$'}installer_ready" != 1 ] && maidroid_fetch_uv_installer ${shellQuote(UV_INSTALLER_URL)}; then
                installer_ready=1
            fi
            """.trimIndent()
        }
        val installBinaryBlock = if (preferDirect) {
            """
            # 下载基址留空时安装脚本走官方源；镜像基址形如 https://镜像/https://github.com。
            maidroid_install_uv_via "releases.astral.sh / github.com" "" && exit 0
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                [ -n "${'$'}mirror_prefix" ] || continue
                maidroid_install_uv_via "${'$'}mirror_name" "${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" https://github.com)" && exit 0
            done
            """.trimIndent()
        } else {
            """
            # 加速镜像或用户自定义镜像优先：先按有序镜像尝试，全失败再回退 astral.sh 官方源。
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                if [ -n "${'$'}mirror_prefix" ]; then
                    maidroid_install_uv_via "${'$'}mirror_name" "${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" https://github.com)" && exit 0
                else
                    maidroid_install_uv_via "releases.astral.sh / github.com" "" && exit 0
                fi
            done
            maidroid_install_uv_via "releases.astral.sh / github.com" "" && exit 0
            """.trimIndent()
        }

        return ContainerScript(
            listOf(
                GitHubMirrors.shellFunctions(ordered),
                """
                uv_bin="${'$'}HOME/.local/bin/uv"
                if uv_version="${'$'}("${'$'}uv_bin" --version 2>/dev/null)"; then
                    printf "[uv] already installed: %s\n" "${'$'}uv_version"
                    exit 0
                fi

                work_dir="${'$'}(mktemp -d)" || exit 1
                trap 'rm -rf "${'$'}work_dir"' EXIT
                installer="${'$'}work_dir/uv-installer.sh"

                # 安装脚本里的 curl 没设超时，镜像卡住时会一直等下去。用临时 .curlrc 给本脚本里所有 curl 加上：
                # 20 秒连不上、或连续 30 秒低于 1 KB/s 就放弃，换下一个来源。
                printf 'connect-timeout = 20\nspeed-limit = 1024\nspeed-time = 30\n' > "${'$'}work_dir/.curlrc"
                export CURL_HOME="${'$'}work_dir"

                # 下载到文件再执行，并确认下到的是 shell 脚本：镜像出错时可能返回 200 的网页。
                # 只看开头的 #! 和里面提到 uv 的发布地址，不依赖安装脚本内部的写法，Astral 改版也不会被误判。
                maidroid_fetch_uv_installer() {
                    printf "[uv] downloading installer: %s\n" "${'$'}1"
                    rm -f "${'$'}installer"
                    curl -fsSL -o "${'$'}installer" "${'$'}1" &&
                        head -n 1 "${'$'}installer" | grep -q '^#!' &&
                        grep -q 'astral-sh/uv' "${'$'}installer"
                }

                maidroid_install_uv_via() {
                    printf "[uv] installing via %s\n" "${'$'}1"
                    UV_INSTALLER_GITHUB_BASE_URL="${'$'}2" sh "${'$'}installer" &&
                        uv_version="${'$'}("${'$'}uv_bin" --version)" &&
                        printf "[uv] installed: %s\n" "${'$'}uv_version"
                }

                installer_ready=0
                $fetchScriptBlock
                if [ "${'$'}installer_ready" != 1 ]; then
                    printf "下载不到 uv 安装脚本（astral.sh 和所有 GitHub 镜像都失败），请检查网络后重试\n"
                    exit 1
                fi

                $installBinaryBlock
                printf "uv 安装失败：官方源和所有 GitHub 镜像都没能装上可运行的 uv，请检查网络后重试\n"
                exit 1
                """.trimIndent()
            ).joinToString("\n")
        )
    }

    /**
     * 准备 uv sync 要用的 Python 解释器：已有符合 UV_PYTHON 的（uv 管理的或系统的）就跳过。
     * 依 [gitHubMirrors] 排序决定先走官方源还是先走测速最快/用户指定的 GitHub 镜像。
     */
    fun installPython(gitHubMirrors: List<GitHubMirror> = GitHubMirrors.ALL): ContainerScript {
        val ordered = gitHubMirrors.ifEmpty { GitHubMirrors.ALL }
        val preferDirect = ordered.first().isDirect
        val downloadBlock = if (preferDirect) {
            """
            printf "[uv] downloading Python %s via official source\n" "${'$'}UV_PYTHON"
            uv python install "${'$'}UV_PYTHON" && exit 0
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                printf "[uv] downloading Python %s via %s\n" "${'$'}UV_PYTHON" "${'$'}mirror_name"
                UV_PYTHON_INSTALL_MIRROR="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" ${shellQuote(PYTHON_DOWNLOAD_GITHUB_URL)})" \
                    uv python install "${'$'}UV_PYTHON" && exit 0
            done
            """.trimIndent()
        } else {
            """
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                printf "[uv] downloading Python %s via %s\n" "${'$'}UV_PYTHON" "${'$'}mirror_name"
                UV_PYTHON_INSTALL_MIRROR="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" ${shellQuote(PYTHON_DOWNLOAD_GITHUB_URL)})" \
                    uv python install "${'$'}UV_PYTHON" && exit 0
            done
            printf "[uv] downloading Python %s via official source\n" "${'$'}UV_PYTHON"
            uv python install "${'$'}UV_PYTHON" && exit 0
            """.trimIndent()
        }

        return ContainerScript(
            listOf(
                GitHubMirrors.shellFunctions(ordered),
                uvPrelude(),
                requireUvScript(),
                """
                # 在 /tmp 里找，避开 MaiBot 目录下 uv sync 失败后残留的 .venv。
                cd /tmp || exit 1
                if python_path="${'$'}(uv python find "${'$'}UV_PYTHON" 2>/dev/null)"; then
                    printf "[uv] using Python %s: %s\n" "${'$'}UV_PYTHON" "${'$'}python_path"
                    exit 0
                fi

                $downloadBlock
                printf "Python 解释器下载失败（官方源和所有 GitHub 镜像都失败），请检查网络后重试\n"
                exit 1
                """.trimIndent()
            ).joinToString("\n")
        )
    }

    /**
     * 在 MaiBot 目录执行 uv sync 建好 .venv：
     * 1. 先检查 [pyPiMirrors] 首选（自动测速最快源或用户指定/自定义源）是否与当前生效的 PYPI_INDEX_FILE 一致，
     *    若不一致则立即切换 UV_INDEX 并更新记忆文件（同时清掉锁死旧源的 uv.lock）；
     * 2. 下载失败按 [pyPiMirrors] 剩余顺序自动换源兜底并记住成功项；
     * 3. 非下载问题时尝试删除损坏的 .venv 重建一次。
     */
    fun syncDependencies(pyPiMirrors: List<PyPiMirror> = PyPiMirrors.ALL): ContainerScript {
        val ordered = pyPiMirrors.ifEmpty { PyPiMirrors.ALL }
        val preferred = ordered.first()
        val indexSpecs = ordered.joinToString(" ") { shellQuote("${it.name}|${it.indexUrl}") }
        return ContainerScript(
            body = listOf(
                uvPrelude(),
                requireUvScript(),
                """
                cd ${shellQuote(MAIBOT_DIR)} || exit 1

                # 将测速优选或用户在设置里选定/自定义的 PyPI 源置为本轮首选，防止沿用过期记忆源
                preferred_pypi_name=${shellQuote(preferred.name)}
                preferred_pypi_url=${shellQuote(preferred.indexUrl)}
                if [ "${'$'}{UV_INDEX:-}" != "${'$'}preferred_pypi_url" ]; then
                    printf "[uv] applying PyPI mirror: %s\n" "${'$'}preferred_pypi_name"
                    rm -f uv.lock
                    if [ -n "${'$'}preferred_pypi_url" ]; then
                        export UV_INDEX="${'$'}preferred_pypi_url"
                        mkdir -p ${shellQuote(PYPI_INDEX_FILE.substringBeforeLast('/'))}
                        printf '%s\n' "${'$'}preferred_pypi_url" > ${shellQuote(PYPI_INDEX_FILE)}
                    else
                        unset UV_INDEX
                        rm -f ${shellQuote(PYPI_INDEX_FILE)}
                    fi
                fi

                # proot -0 下部分 wheel（如 opencv-python-headless）会解出只读目录，导致非 root 内核里 uv sync / rm -rf 报 EACCES。
                maidroid_fix_venv_perms() {
                    if [ -d .venv ]; then
                        chmod -R u+rwX .venv 2>/dev/null || true
                    fi
                }
                maidroid_remove_venv() {
                    maidroid_fix_venv_perms
                    rm -rf .venv 2>/dev/null || true
                    if [ -e .venv ]; then
                        chmod -R u+rwX .venv 2>/dev/null || true
                        rm -rf .venv 2>/dev/null || true
                    fi
                    if [ -e .venv ]; then
                        mv .venv ".venv.broken.${'$'}${'$'}" 2>/dev/null || true
                    fi
                }
                for broken_venv in .venv.broken.*; do
                    [ -e "${'$'}broken_venv" ] || continue
                    chmod -R u+rwX "${'$'}broken_venv" 2>/dev/null || true
                    rm -rf "${'$'}broken_venv" 2>/dev/null || true
                done
                maidroid_fix_venv_perms

                # 跑一次 uv sync：输出照常打到终端，同时留一份，失败后据此判断怎么修。
                maidroid_uv_sync() {
                    { uv sync; echo "${'$'}?" > /tmp/maidroid-uv.status; } 2>&1 | tee /tmp/maidroid-uv.log
                    return "${'$'}(cat /tmp/maidroid-uv.status 2>/dev/null || echo 1)"
                }
                maidroid_uv_output_has() {
                    grep -qiE "${'$'}1" /tmp/maidroid-uv.log 2>/dev/null
                }
                maidroid_uv_download_failed() {
                    maidroid_uv_output_has '$UV_DOWNLOAD_FAILED_PATTERN' && ! maidroid_uv_output_has '$UV_NOT_FIXABLE_PATTERN'
                }

                maidroid_uv_sync && exit 0

                if maidroid_uv_download_failed; then
                    pypi_tried="|${'$'}{UV_INDEX:-}|"
                    for pypi_spec in $indexSpecs; do
                        pypi_name="${'$'}{pypi_spec%%|*}"
                        pypi_url="${'$'}{pypi_spec#*|}"
                        case "${'$'}pypi_tried" in
                            *"|${'$'}pypi_url|"*) continue ;;
                        esac
                        pypi_tried="${'$'}pypi_tried${'$'}pypi_url|"
                        printf "$AUTO_FIX_LOG_PREFIX 依赖包下载失败，换成%s重试\n" "${'$'}pypi_name"
                        rm -f uv.lock
                        if [ -n "${'$'}pypi_url" ]; then export UV_INDEX="${'$'}pypi_url"; else unset UV_INDEX; fi
                        if maidroid_uv_sync; then
                            if [ -n "${'$'}pypi_url" ]; then
                                mkdir -p ${shellQuote(PYPI_INDEX_FILE.substringBeforeLast('/'))}
                                printf '%s\n' "${'$'}pypi_url" > ${shellQuote(PYPI_INDEX_FILE)}
                            else
                                rm -f ${shellQuote(PYPI_INDEX_FILE)}
                            fi
                            printf "$AUTO_FIX_LOG_PREFIX 已改用%s，之后装依赖和启动都走这个源\n" "${'$'}pypi_name"
                            exit 0
                        fi
                        # 换了源之后不再是下载问题（例如依赖冲突），再换也没用
                        maidroid_uv_download_failed || break
                    done
                    if maidroid_uv_download_failed; then
                        printf "依赖包下载失败（各个 PyPI 镜像都试过了），请检查网络后重试\n"
                        exit 1
                    fi
                fi

                if [ -d .venv ] && ! maidroid_uv_output_has '$UV_NOT_REBUILDABLE_PATTERN'; then
                    printf "$AUTO_FIX_LOG_PREFIX uv sync 失败，虚拟环境可能已损坏，删掉 .venv 重建一次\n"
                    maidroid_remove_venv
                    maidroid_uv_sync && exit 0
                fi
                exit 1
                """.trimIndent()
            ).joinToString("\n"),
            workingDirectory = MAIBOT_DIR
        )
    }

    /** 换源成功后记下来的 PyPI 源地址，uvPrelude 读它设 UV_INDEX；文件不在表示用 MaiBot 自带的源。 */
    const val PYPI_INDEX_FILE = "/root/.maidroid/pypi-index"

    // uv 输出里出现这些，说明是下载出了问题，换个源有希望（镜像缺包时是 404）。
    private const val UV_DOWNLOAD_FAILED_PATTERN =
        "Failed to fetch|Failed to download|error sending request|dns error|failed to lookup address|" +
            "timed out|Connection reset|Connection refused|Network is unreachable|Request failed after|" +
            "HTTP status (client|server) error|connection closed|unexpected EOF"

    // 空间满、手机时间不对（证书还没生效）：换源、重建都一样失败。
    private const val UV_NOT_FIXABLE_PATTERN = "No space left on device|NotValidYet|not yet valid"

    // 依赖冲突、源码编译失败：问题在依赖本身，重建 .venv 也没用。
    private const val UV_NOT_REBUILDABLE_PATTERN =
        "$UV_NOT_FIXABLE_PATTERN|No solution found|unsatisfiable|Failed to build|Build backend failed"

    /**
     * 前台运行 MaiBot：优先直接执行 .venv/bin/python 并用 exec 原地替换外层 /bin/sh，
     * 省掉 script 伪终端与 uv 包装进程占用的幻影进程名额；依赖 PYTHONUNBUFFERED=1 按行实时刷新输出。
     * 仅在 .venv 缺失时回退 exec uv run python bot.py；启动前需要注入 MaiBotAgreement 的协议 hash。
     */
    fun launch(): ContainerScript = ContainerScript(
        body = "${uvPrelude()}; cd ${shellQuote(MAIBOT_DIR)} && " +
            "if [ -d ${shellQuote("$MAIBOT_DIR/.venv")} ]; then " +
            "chmod u+rx ${shellQuote("$MAIBOT_DIR/.venv")} ${shellQuote("$MAIBOT_DIR/.venv/bin")} 2>/dev/null || true; " +
            "fi; " +
            "export PYTHONUNBUFFERED=1 VIRTUAL_ENV=${shellQuote("$MAIBOT_DIR/.venv")} " +
            "PATH=${shellQuote("$MAIBOT_DIR/.venv/bin")}:\"\$PATH\"; " +
            "if [ -x ${shellQuote("$MAIBOT_DIR/.venv/bin/python")} ]; then " +
            "exec ${shellQuote("$MAIBOT_DIR/.venv/bin/python")} bot.py; " +
            "else exec uv run python bot.py; fi",
        workingDirectory = MAIBOT_DIR,
        excludeTopCpuCluster = true
    )

    /**
     * 停止、重启和安全退出时，请 MaiBot 的 Runner（python bot.py）自己退出，最多等 EXIT_TIMEOUT_SECONDS 秒。
     * Runner 收到 SIGTERM 后转给 Worker（python -m src.runtime）并等它走完优雅关闭
     * （停插件运行时、持久化 A_Memorix 记忆、关数据库），Worker 退出后 Runner 跟着退出，
     * proot 也就结束了。2026-11 真机实测 MaiBot 1.3.19 空闲时约 3 秒关完。
     * 只给 Runner 发，这是 MaiBot 自己的停止入口；外层 proot/sh 包装命令行及旧式 uv run 用 exclude 排除掉。
     */
    fun requestExit(): ContainerScript = ContainerScript(
        listOf(
            "set +e",
            requestExitScript(EXIT_TARGET, timeoutSeconds = EXIT_TIMEOUT_SECONDS, logPrefix = "[MaiBot] ")
        ).joinToString("\n")
    )

    /** requestExit 要请其退出的进程：Runner（python bot.py 或 .venv/bin/python bot.py），排除外层 proot/sh 包装与 uv run。 */
    val EXIT_TARGET = ExitTarget(
        pattern = "python[0-9.]* bot[.]py",
        exclude = "libproot_exec|sh -c|uv run",
        processName = "MaiBot"
    )

    // 宿主侧清理遗留 MaiBot 进程（MaiBotProcessSupervisor.cleanupStaleProcesses）按命令行匹配的正则。
    // 覆盖旧版脚本与新版 MaiBot 的 bootstrap/process 进程；中间用点号替代路径斜杠，避免正则转义冲突。
    const val STALE_PROCESS_PATTERN =
        "python[0-9.]* bot[.]py|src[.]host[.]bootstrap|src.host.process[.]py|python[0-9.]* -m src[.]runtime|src[.]plugin_runtime[.]runner[.]runner_main"

    // 同一份环境变量同时作用于 uv sync、uv run 和在线更新：先限定 Python 版本，再跳过 dev 依赖，
    // 保证“安装依赖”和“启动时 uv run 自动同步”用同一套参数，不会因为参数不一致反复重建 .venv。
    // dev 组里的 zstandard 等测试依赖在运行时用不到，却可能触发源码编译。
    // 装依赖时换过 PyPI 源的，这里接着用那个源（见 syncDependencies），否则 uv 会按 MaiBot 自带的源重新解析。
    private fun uvPrelude(): String =
        "export UV_PYTHON=${shellQuote(UV_PYTHON_REQUEST)}; " +
            "export UV_NO_DEV=1; " +
            "export UV_LINK_MODE=copy; " +
            "if [ -s ${shellQuote(PYPI_INDEX_FILE)} ]; then export UV_INDEX=\"\$(cat ${shellQuote(PYPI_INDEX_FILE)})\"; fi; " +
            "export PATH=\"\$HOME/.local/bin:\$PATH\"; " +
            "if [ -f \"\$HOME/.local/bin/env\" ]; then . \"\$HOME/.local/bin/env\"; fi"

    // 接在 uvPrelude 后面：uv 跑不起来就直接用中文说明退出，不让后面的命令只留下一句 uv: not found。
    private fun requireUvScript(): String =
        """
        if ! uv --version >/dev/null 2>&1; then
            printf "找不到可用的 uv，请重试\n"
            exit 127
        fi
        """.trimIndent()
}
