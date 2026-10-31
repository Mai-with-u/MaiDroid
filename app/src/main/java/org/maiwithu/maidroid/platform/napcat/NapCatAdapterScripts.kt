package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * MaiBot 侧连 NapCat 的 QQ 适配器插件：来源、版本要求和安装脚本。
 *
 * 用的是 MaiBot-SnowLuma-Adapter：它同时支持 SnowLuma 和 NapCat，连上后按对端自动切换。
 * 原来的 MaiBot-Napcat-Adapter 已停更（清单名写着“不再更新，请使用Snowluma适配器”），
 * 安装新适配器时会把它删掉，两个适配器同时连一个 NapCat 会把每条消息收两遍。
 */
internal object NapCatAdapterScripts {
    const val REPOSITORY_URL = "https://github.com/Mai-with-u/MaiBot-SnowLuma-Adapter.git"

    /** MaiDroid 自己安装时用的插件目录名；从插件市场装的副本目录名不同，按 PLUGIN_ID 识别。 */
    const val DIR_NAME = "MaiBot-SnowLuma-Adapter"

    /** 插件清单 _manifest.json 里的 id，MaiBot 按它区分插件。 */
    const val PLUGIN_ID = "maibot-team.snowluma-adapter"

    /** 适配器清单 host_application.min_version：MaiBot 低于这个版本会拒绝加载它。 */
    const val MIN_MAIBOT_VERSION = "1.3.18"

    /** 已停更的旧适配器的清单 id 和 MaiDroid 以前安装用的目录名。 */
    const val LEGACY_PLUGIN_ID = "maibot-team.napcat-adapter"
    const val LEGACY_DIR_NAME = "MaiBot-Napcat-Adapter"

    /**
     * 安装新适配器并删掉旧适配器：
     * 还没装（cloneAdapter 为真）时经 GitHub 镜像克隆到 plugins 下的点开头临时目录 -> 改名为正式目录 ->
     * 再删掉清单 id 是旧适配器的所有目录（MaiDroid 装的和插件市场装的都算）。
     * 先装新的再删旧的：克隆失败时脚本在 set -e 下直接退出，旧适配器还在，QQ 不会断。
     * 临时目录以点开头，MaiBot 扫插件和热重载时会跳过它；core.createObject=rename 避免 proot 把
     * packfile 硬链接变成绝对路径符号链接，改名后仓库仍可读。连接配置由宿主侧另写，不在脚本里。
     */
    fun install(cloneAdapter: Boolean): ContainerScript {
        val pluginsDir = InContainerPaths.MAIBOT_PLUGINS_DIR
        val adapterDir = "$pluginsDir/$DIR_NAME"
        val stagingDir = "$pluginsDir/.$DIR_NAME.tmp"
        val legacyIdPattern = """"id"[[:space:]]*:[[:space:]]*"${LEGACY_PLUGIN_ID.replace(".", "\\.")}""""
        val cloneStep = if (cloneAdapter) {
            """
            mkdir -p ${shellQuote(pluginsDir)}
            rm -rf ${shellQuote(stagingDir)}
            maidroid_github_clone ${shellQuote(REPOSITORY_URL)} ${shellQuote(stagingDir)} -c core.createObject=rename --depth=1 --single-branch
            rm -rf ${shellQuote(adapterDir)}
            mv ${shellQuote(stagingDir)} ${shellQuote(adapterDir)}
            printf "[NapCat] QQ 适配器已下载到 %s\n" ${shellQuote(adapterDir)}
            """.trimIndent()
        } else {
            """printf "[NapCat] QQ 适配器已存在，跳过下载\n""""
        }
        return ContainerScript(
            listOf(
                GitHubMirrors.shellFunctions(),
                "set -e",
                cloneStep,
                """
                for manifest in ${shellQuote(pluginsDir)}/*/_manifest.json; do
                    [ -f "${'$'}manifest" ] || continue
                    if grep -qiE ${shellQuote(legacyIdPattern)} "${'$'}manifest"; then
                        legacy_dir="${'$'}(dirname "${'$'}manifest")"
                        printf "[NapCat] 删除已停更的旧 NapCat 适配器: %s\n" "${'$'}legacy_dir"
                        rm -rf "${'$'}legacy_dir"
                    fi
                done
                """.trimIndent()
            ).joinToString("\n")
        )
    }
}
