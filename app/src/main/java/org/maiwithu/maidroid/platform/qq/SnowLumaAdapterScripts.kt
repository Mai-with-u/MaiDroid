package org.maiwithu.maidroid.platform.qq

import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.shellQuote

/**
 * MaiBot 侧连 QQ 协议端的 SnowLuma 适配器插件（MaiBot-SnowLuma-Adapter）：来源与暂存克隆脚本。
 *
 * 名字里的 SnowLuma 是适配器的名字：这个适配器同时支持 SnowLuma 客户端和 NapCat 两种 QQ 协议端，
 * 连上后按对端（get_version_info 的 app_name）自动切换，MaiDroid 启用哪种协议端它就连哪种（地址都是 QqOneBot）。
 * 原来的 MaiBot-Napcat-Adapter 已停更（清单名写着“不再更新，请使用Snowluma适配器”），
 * 只有在新适配器安装并校验版本兼容后，才由宿主侧删除旧适配器，两个适配器同时连一个协议端会把每条消息收两遍。
 * 界面和终端日志里对用户说的“QQ 适配器”就是它。
 */
internal object SnowLumaAdapterScripts {
    const val REPOSITORY_URL = "https://github.com/Mai-with-u/MaiBot-SnowLuma-Adapter.git"

    /** MaiDroid 自己安装时用的插件目录名；从插件市场装的副本目录名不同，按 PLUGIN_ID 识别。 */
    const val DIR_NAME = "MaiBot-SnowLuma-Adapter"

    /** 克隆暂存目录名（以点开头，MaiBot 扫插件和热重载时会跳过它）。 */
    const val STAGING_DIR_NAME = ".$DIR_NAME.tmp"

    /** 插件清单 _manifest.json 里的 id，MaiBot 按它区分插件。 */
    const val PLUGIN_ID = "maibot-team.snowluma-adapter"

    /** 已停更的旧适配器的清单 id 和 MaiDroid 以前安装用的目录名。 */
    const val LEGACY_PLUGIN_ID = "maibot-team.napcat-adapter"
    const val LEGACY_DIR_NAME = "MaiBot-Napcat-Adapter"

    /**
     * 只把新适配器经 GitHub 镜像克隆到 plugins 下的点开头暂存目录，不直接覆盖正式目录、也不删旧适配器：
     * 宿主侧先读暂存目录里的 `_manifest.json` 核对 `host_application` 与当前 MaiBot 是否兼容，
     * 兼容才扶正并清理旧适配器；不兼容时删掉暂存目录，旧适配器与当前配置原封不动。
     * core.createObject=rename 避免 proot 把 packfile 硬链接变成绝对路径符号链接，改名后仓库仍可读。
     */
    fun cloneToStaging(
        logPrefix: String = QqClient.NapCat.logPrefix,
        pluginsDir: String = InContainerPaths.MAIBOT_PLUGINS_DIR
    ): ContainerScript {
        val stagingDir = "${pluginsDir.trimEnd('/')}/$STAGING_DIR_NAME"
        return ContainerScript(
            listOf(
                GitHubMirrors.shellFunctions(),
                """
                set -e
                mkdir -p ${shellQuote(pluginsDir)}
                rm -rf ${shellQuote(stagingDir)}
                maidroid_github_clone ${shellQuote(REPOSITORY_URL)} ${shellQuote(stagingDir)} -c core.createObject=rename --depth=1 --single-branch
                printf "%s QQ 适配器已下载到暂存目录，正在检查版本兼容性\n" ${shellQuote(logPrefix)}
                """.trimIndent()
            ).joinToString("\n")
        )
    }
}
