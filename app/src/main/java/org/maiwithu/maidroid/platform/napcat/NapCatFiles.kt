package org.maiwithu.maidroid.platform.napcat

import org.maiwithu.maidroid.platform.qq.QqAdapterFiles
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/** NapCat 相关文件在宿主机侧（容器 rootfs 内）的位置，以及“是否已装好”的判断。 */
internal class NapCatFiles(paths: RuntimePaths) {
    /** MaiBot 侧的 SnowLuma 适配器和 OneBot token，和 SnowLuma 共用。 */
    val adapter = QqAdapterFiles(paths)

    /** 容器内 /root/napcat：NapCat 本体、配置和登录二维码缓存。 */
    val napCatDir: File = File(paths.rootfsDir, "root/napcat")
    val configDir: File = File(napCatDir, "config")

    /** 安装脚本写的默认 OneBot 配置；QQ 登录后 NapCat 会另存一份 onebot11_<QQ号>.json。 */
    val defaultOneBotConfig: File = File(configDir, "onebot11.json")

    /** NapCat 自带 WebUI 的端口和 token。 */
    val webUiConfig: File = File(configDir, "webui.json")

    /** NapCat 写出的 QQ 登录二维码，换码时会被覆盖。 */
    val qrCodeFile: File = File(napCatDir, "cache/qrcode.png")

    /** 安装脚本最后一步写的完成标记。 */
    val readyMarker: File = File(paths.rootfsDir, "root/.maidroid/napcat_ready")

    /** 启动 Xvfb 和 LinuxQQ 的脚本，每次启动前由 NapCatScripts.prepareRuntime 重新生成。 */
    val launcher: File = File(paths.rootfsDir, "root/launcher.sh")

    /** QQ 本地登录数据目录，读不到 OneBot 配置时从这里的子目录名推断 QQ 号。 */
    val qqLoginDataDir: File = File(paths.rootfsDir, "root/.config/QQ/nt_qq/global/nt_data/Login")

    /** 安装脚本汇报进度的两个文件：容器内 $TMPDIR/progress 和 progress_des，宿主侧在 termux/tmp。 */
    val installProgressFile: File = File(paths.termuxTmpDir, "progress")
    val installProgressDescriptionFile: File = File(paths.termuxTmpDir, "progress_des")

    /** NapCat 本体装好了（安装标记和 OneBot 配置都在），可以启动 QQ；不管 MaiBot 侧的适配器。 */
    fun isNapCatReady(): Boolean = readyMarker.isFile && defaultOneBotConfig.isFile

    /** NapCat 本体和 SnowLuma 适配器都装好、适配器配置也迁移好了才算装好，消息平台页按这个显示。 */
    fun isInstalled(): Boolean = isNapCatReady() && adapter.isAdapterConfigured()
}
