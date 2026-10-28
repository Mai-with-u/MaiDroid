package org.maiwithu.maidroid.maibot

import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
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
    fun clone(): ContainerScript = ContainerScript(
        listOf(
            GitHubMirrors.shellFunctions(),
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
     * 拿安装脚本：astral.sh -> GitHub 发布页（直连和各镜像）->
     * 安装：先走官方下载源（releases.astral.sh、github.com），失败后把下载基址依次换成各 GitHub 镜像重试 ->
     * 每次装完都跑一次 uv --version，能运行才算成功。安装脚本里带着安装包的 sha256，经镜像下载的包也会校验。
     * 以前是 curl … | sh：管道的退出码只看 sh，curl 下载失败时 sh 读到空脚本照样返回 0，
     * 安装被当成成功，到 uv sync 才报 uv: not found。
     */
    fun installUv(): ContainerScript = ContainerScript(
        listOf(
            GitHubMirrors.shellFunctions(),
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

            # 下载到文件再执行，并确认内容真是 uv 的安装脚本：镜像出错时可能返回 200 的网页。
            maidroid_fetch_uv_installer() {
                printf "[uv] downloading installer: %s\n" "${'$'}1"
                rm -f "${'$'}installer"
                curl -fsSL -o "${'$'}installer" "${'$'}1" && grep -q '^APP_NAME="uv"' "${'$'}installer"
            }

            maidroid_install_uv_via() {
                printf "[uv] installing via %s\n" "${'$'}1"
                UV_INSTALLER_GITHUB_BASE_URL="${'$'}2" sh "${'$'}installer" &&
                    uv_version="${'$'}("${'$'}uv_bin" --version)" &&
                    printf "[uv] installed: %s\n" "${'$'}uv_version"
            }

            installer_ready=0
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
            if [ "${'$'}installer_ready" != 1 ]; then
                printf "下载不到 uv 安装脚本（astral.sh 和所有 GitHub 镜像都失败），请检查网络后重试\n"
                exit 1
            fi

            # 下载基址留空时安装脚本走官方源；镜像基址形如 https://镜像/https://github.com。
            maidroid_install_uv_via "releases.astral.sh / github.com" "" && exit 0
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                [ -n "${'$'}mirror_prefix" ] || continue
                maidroid_install_uv_via "${'$'}mirror_name" "${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" https://github.com)" && exit 0
            done
            printf "uv 安装失败：官方源和所有 GitHub 镜像都没能装上可运行的 uv，请检查网络后重试\n"
            exit 1
            """.trimIndent()
        ).joinToString("\n")
    )

    /**
     * 准备 uv sync 要用的 Python 解释器：已有符合 UV_PYTHON 的（uv 管理的或系统的）就跳过。
     * 容器里没有系统 Python，uv sync 会自己去官方源（releases.astral.sh、github.com）下载；这一步先单独装好，
     * 官方源失败时再经 GitHub 镜像（含直连）下载 python-build-standalone，
     * 下载失败和装包失败也就能分开报。
     */
    fun installPython(): ContainerScript = ContainerScript(
        listOf(
            GitHubMirrors.shellFunctions(),
            uvPrelude(),
            requireUvScript(),
            """
            # 在 /tmp 里找，避开 MaiBot 目录下 uv sync 失败后残留的 .venv。
            cd /tmp || exit 1
            if python_path="${'$'}(uv python find "${'$'}UV_PYTHON" 2>/dev/null)"; then
                printf "[uv] using Python %s: %s\n" "${'$'}UV_PYTHON" "${'$'}python_path"
                exit 0
            fi

            printf "[uv] downloading Python %s via official source\n" "${'$'}UV_PYTHON"
            uv python install "${'$'}UV_PYTHON" && exit 0
            for mirror_spec in ${'$'}(maidroid_github_mirror_specs); do
                mirror_name="${'$'}{mirror_spec%%|*}"
                mirror_prefix="${'$'}{mirror_spec#*|}"
                printf "[uv] downloading Python %s via %s\n" "${'$'}UV_PYTHON" "${'$'}mirror_name"
                UV_PYTHON_INSTALL_MIRROR="${'$'}(maidroid_github_candidate_url "${'$'}mirror_prefix" ${shellQuote(PYTHON_DOWNLOAD_GITHUB_URL)})" \
                    uv python install "${'$'}UV_PYTHON" && exit 0
            done
            printf "Python 解释器下载失败（官方源和所有 GitHub 镜像都失败），请检查网络后重试\n"
            exit 1
            """.trimIndent()
        ).joinToString("\n")
    )

    /** 在 MaiBot 目录执行 uv sync，按 uv.lock 建好 .venv。 */
    fun syncDependencies(): ContainerScript = ContainerScript(
        body = listOf(
            uvPrelude(),
            requireUvScript(),
            "cd ${shellQuote(MAIBOT_DIR)} && uv sync"
        ).joinToString("\n"),
        workingDirectory = MAIBOT_DIR
    )

    /**
     * 前台运行 MaiBot。有 script 命令时套一层伪终端，让 MaiBot 的彩色日志按行刷新到终端页；
     * 启动前需要注入 MaiBotAgreement 的协议 hash，否则 MaiBot 会停在 EULA 确认。
     */
    fun launch(): ContainerScript = ContainerScript(
        body = "${uvPrelude()}; cd ${shellQuote(MAIBOT_DIR)} && " +
            "if command -v script >/dev/null 2>&1; then " +
            "script -q -c ${shellQuote("uv run python bot.py")} /dev/null; " +
            "else uv run python bot.py; fi",
        workingDirectory = MAIBOT_DIR
    )

    /** 结束上次异常退出留下的 MaiBot 及其插件运行器进程，避免 WebUI 端口被占。 */
    fun cleanupStaleProcesses(): ContainerScript = ContainerScript(
        listOf(
            "set +e",
            killMatchingProcessesScript(
                pattern = "uv run python bot.py|python3 bot.py|src[.]plugin_runtime[.]runner[.]runner_main",
                processName = "stale MaiBot process",
                logPrefix = "[MaiBot] "
            ),
            "exit 0"
        ).joinToString("\n")
    )

    // 同一份环境变量同时作用于 uv sync、uv run 和在线更新：先限定 Python 版本，再跳过 dev 依赖，
    // 保证“安装依赖”和“启动时 uv run 自动同步”用同一套参数，不会因为参数不一致反复重建 .venv。
    // dev 组里的 zstandard 等测试依赖在运行时用不到，却可能触发源码编译。
    private fun uvPrelude(): String =
        "export UV_PYTHON=${shellQuote(UV_PYTHON_REQUEST)}; " +
            "export UV_NO_DEV=1; " +
            "export UV_LINK_MODE=copy; " +
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
