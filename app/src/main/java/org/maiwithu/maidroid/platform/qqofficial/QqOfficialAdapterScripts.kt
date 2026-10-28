package org.maiwithu.maidroid.platform.qqofficial

import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.shellQuote

/** QQ 官方机器人适配器（MaiBot-QQ-Adapter 插件）的来源与安装脚本。 */
internal object QqOfficialAdapterScripts {
    const val REPOSITORY_URL = "https://github.com/Mai-with-u/MaiBot-QQ-Adapter.git"

    /** MaiDroid 自己安装时用的插件目录名；从插件市场装的副本目录名不同，按 PLUGIN_ID 识别。 */
    const val DIR_NAME = "MaiBot-QQ-Adapter"

    /** 插件清单 _manifest.json 里的 id，MaiBot 按它区分插件。 */
    const val PLUGIN_ID = "Mai-with-u.qq-adapter"

    /**
     * 安装 QQ 官方机器人适配器：已装好就直接退出 -> 经 GitHub 镜像克隆到 plugins 下的点开头临时目录 ->
     * 改名为正式目录。临时目录以点开头，MaiBot 扫插件和热重载时会跳过它，不会把克隆了一半的目录当插件加载；
     * core.createObject=rename 避免 proot 把 packfile 硬链接变成绝对路径符号链接，改名后仓库仍可读。
     * 凭据由宿主侧直接写 config.toml，不经过命令行，避免 AppSecret 出现在进程参数和日志里。
     */
    fun install(): ContainerScript {
        val pluginsDir = InContainerPaths.MAIBOT_PLUGINS_DIR
        val adapterDir = "$pluginsDir/$DIR_NAME"
        val stagingDir = "$pluginsDir/.$DIR_NAME.tmp"
        return ContainerScript(
            listOf(
                GitHubMirrors.shellFunctions(),
                """
                set -e
                if [ -f ${shellQuote("$adapterDir/_manifest.json")} ] && [ -f ${shellQuote("$adapterDir/plugin.py")} ]; then
                    printf "[QQ 官方机器人] 适配器已存在，跳过下载: %s\n" ${shellQuote(adapterDir)}
                    exit 0
                fi
                mkdir -p ${shellQuote(pluginsDir)}
                rm -rf ${shellQuote(stagingDir)}
                maidroid_github_clone ${shellQuote(REPOSITORY_URL)} ${shellQuote(stagingDir)} -c core.createObject=rename --depth=1 --single-branch
                rm -rf ${shellQuote(adapterDir)}
                mv ${shellQuote(stagingDir)} ${shellQuote(adapterDir)}
                printf "[QQ 官方机器人] 适配器已下载到 %s\n" ${shellQuote(adapterDir)}
                """.trimIndent()
            ).joinToString("\n")
        )
    }
}
