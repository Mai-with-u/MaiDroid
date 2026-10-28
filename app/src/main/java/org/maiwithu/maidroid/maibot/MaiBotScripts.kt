package org.maiwithu.maidroid.maibot

import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.killMatchingProcessesScript
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * MaiBot 本体在容器里的安装与运行脚本，对应上游 installation.md 的手动步骤：
 * 克隆源码 -> 安装 uv -> uv sync 装依赖 -> uv run python bot.py 启动。
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

    /** 用 uv 官方安装脚本把 uv 装到 /root/.local/bin。 */
    fun installUv(): ContainerScript = ContainerScript("curl -LsSf https://astral.sh/uv/install.sh | sh")

    /** 在 MaiBot 目录执行 uv sync，按 uv.lock 建好 .venv。 */
    fun syncDependencies(): ContainerScript = ContainerScript(
        body = "${uvPrelude()}; cd ${shellQuote(MAIBOT_DIR)} && uv sync",
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
}
