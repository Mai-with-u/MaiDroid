package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.maibot.findPluginDirById
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/** NapCat 相关文件在宿主机侧（容器 rootfs 内）的位置，以及“是否已装好”的判断。 */
internal class NapCatFiles(paths: RuntimePaths) {
    private val maiBotPluginsDir: File = paths.maiBotPluginsDir

    /** 容器内 /root/napcat：NapCat 本体、配置和登录二维码缓存。 */
    val napCatDir: File = File(paths.rootfsDir, "root/napcat")
    val configDir: File = File(napCatDir, "config")

    /** 安装脚本写的默认 OneBot 配置；QQ 登录后 NapCat 会另存一份 onebot11_<QQ号>.json。 */
    val defaultOneBotConfig: File = File(configDir, "onebot11.json")

    /** NapCat 自带 WebUI 的端口和 token。 */
    val webUiConfig: File = File(configDir, "webui.json")

    /** NapCat 写出的 QQ 登录二维码，换码时会被覆盖。 */
    val qrCodeFile: File = File(napCatDir, "cache/qrcode.png")

    /** 第一次安装时生成的 OneBot 访问 token，重装沿用；宿主侧写适配器配置时从这里读。 */
    val oneBotTokenFile: File = File(paths.rootfsDir, "root/.maidroid/napcat_token")

    /** 安装脚本最后一步写的完成标记。 */
    val readyMarker: File = File(paths.rootfsDir, "root/.maidroid/napcat_ready")

    /** 启动 Xvfb 和 LinuxQQ 的脚本，每次启动前由 NapCatScripts.prepareRuntime 重新生成。 */
    val launcher: File = File(paths.rootfsDir, "root/launcher.sh")

    /** MaiBot 的 pyproject.toml，读主程序版本判断能不能装 QQ 适配器。 */
    val maiBotPyproject: File = paths.maiBotPyproject

    /**
     * MaiBot 侧的 QQ 适配器插件（NapCatAdapterScripts）：和 MaiBot 一样按清单 ID 找，
     * 用户也可能从 MaiBot WebUI 的插件市场装过，只认固定目录名会再装一份、同一插件 ID 出现两份会冲突。
     */
    fun adapterDir(): File? =
        findPluginDirById(maiBotPluginsDir, NapCatAdapterScripts.PLUGIN_ID, NapCatAdapterScripts.DIR_NAME)

    /** 已停更的旧适配器（MaiBot-Napcat-Adapter）还在就返回它的目录，迁移时要删掉。 */
    fun legacyAdapterDir(): File? =
        findPluginDirById(maiBotPluginsDir, NapCatAdapterScripts.LEGACY_PLUGIN_ID, NapCatAdapterScripts.LEGACY_DIR_NAME)

    /** QQ 适配器的配置，里面写着连 OneBot 用的端口和 token，由 NapCatAdapterConfig 改写；还没装时指向将要安装的位置。 */
    fun adapterConfig(): File =
        File(adapterDir() ?: File(maiBotPluginsDir, NapCatAdapterScripts.DIR_NAME), "config.toml")

    /** QQ 本地登录数据目录，读不到 OneBot 配置时从这里的子目录名推断 QQ 号。 */
    val qqLoginDataDir: File = File(paths.rootfsDir, "root/.config/QQ/nt_qq/global/nt_data/Login")

    /** 安装脚本汇报进度的两个文件：容器内 $TMPDIR/progress 和 progress_des，宿主侧在 termux/tmp。 */
    val installProgressFile: File = File(paths.termuxTmpDir, "progress")
    val installProgressDescriptionFile: File = File(paths.termuxTmpDir, "progress_des")

    /** NapCat 本体装好了（安装标记和 OneBot 配置都在），可以启动 QQ；不管 MaiBot 侧的适配器。 */
    fun isNapCatReady(): Boolean = readyMarker.isFile && defaultOneBotConfig.isFile

    /** 第一次安装时保存的 OneBot token，读不到返回 null。 */
    fun readOneBotToken(): String? = oneBotTokenFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** QQ 适配器装好了，并且它的配置已经迁移成连本机 NapCat（见 NapCatAdapterConfig.isMigrated）。 */
    fun isAdapterConfigured(): Boolean {
        if (adapterDir() == null) return false
        val config = adapterConfig().takeIf { it.isFile }?.readText() ?: return false
        val token = readOneBotToken() ?: return false
        return NapCatAdapterConfig.isMigrated(config, token)
    }

    /** NapCat 本体和 QQ 适配器都装好、适配器配置也迁移好了才算装好，消息平台页按这个显示。 */
    fun isInstalled(): Boolean = isNapCatReady() && isAdapterConfigured()
}
