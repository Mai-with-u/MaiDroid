package org.maiwithu.maidroid.platform.snowluma

import org.maiwithu.maidroid.platform.qq.QqAdapterFiles
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/**
 * SnowLuma 客户端相关文件在宿主机侧（容器 rootfs 内）的位置，以及“是否已装好”的判断。
 * 容器内的路径常量在 SnowLumaScripts，这里按同样的路径拼出宿主侧的位置。
 */
internal class SnowLumaFiles(paths: RuntimePaths) {
    /** MaiBot 侧的 SnowLuma 适配器和 OneBot token，和 NapCat 共用。 */
    val adapter = QqAdapterFiles(paths)

    private val rootfsDir: File = paths.rootfsDir
    private val tmpDir: File = paths.termuxTmpDir

    /** 发行包解开的程序目录：index.mjs、自带的 node、native/ 下的原生组件。 */
    val appDir: File = inRootfs(SnowLumaScripts.APP_DIR)
    val entry: File = File(appDir, "index.mjs")

    /** SnowLuma 的工作目录（它把 config/、data/、logs/ 都放在工作目录下），和程序目录分开，升级时整个换掉程序目录。 */
    val dataDir: File = inRootfs(SnowLumaScripts.DATA_DIR)
    val configDir: File = File(dataDir, "config")

    /** 安装脚本写的全局 OneBot 配置；QQ 登录后 SnowLuma 会按 QQ 号另存一份 onebot_<QQ号>.json。 */
    val globalOneBotConfig: File = File(configDir, "onebot.json")

    /** 安装脚本最后一步写的完成标记，内容是装的 SnowLuma 版本。 */
    val readyMarker: File = inRootfs(SnowLumaScripts.READY_MARKER)

    /** 用户在安装前同意了 SnowLuma 用户协议和隐私政策的记录；有它，启动时才替用户确认协议。 */
    val consentMarker: File = inRootfs(SnowLumaScripts.CONSENT_MARKER)

    /** SnowLuma 管理页（WebUI）的登录密码，第一次安装时生成，宿主侧登录 WebUI 接口时从这里读。 */
    val webUiPasswordFile: File = inRootfs(SnowLumaScripts.WEBUI_PASSWORD_FILE)

    /** 这次启动给 WebUI 挑的端口，启动脚本写下来，宿主侧访问 WebUI 时读。 */
    val webUiPortFile: File = File(tmpDir, SnowLumaScripts.WEBUI_PORT_FILE.removePrefix("/tmp/"))

    /** 启动 Xvfb、SnowLuma 和 LinuxQQ 的脚本，每次启动前由 SnowLumaScripts.prepareRuntime 重新生成。 */
    val launcher: File = inRootfs(SnowLumaScripts.LAUNCHER)

    /** Xvfb 用 -fbdir 把整块屏幕写成的 XWD 文件：宿主侧直接读它，把 QQ 窗口显示给用户看（登录二维码就在里面）。 */
    val screenFile: File = File(tmpDir, SnowLumaScripts.SCREEN_FILE.removePrefix("/tmp/"))

    /** Xvfb 挑中的显示号，往 QQ 窗口里点击时要用。 */
    val displayFile: File = File(tmpDir, SnowLumaScripts.DISPLAY_FILE.removePrefix("/tmp/"))

    /** QQ 本体（LinuxQQ），和 NapCat 共用同一份。 */
    val qqDir: File = File(rootfsDir, "opt/QQ")

    /** 安装脚本汇报进度的两个文件，和 NapCat 的分开，两边一前一后装时不会读到对方的进度。 */
    val installProgressFile: File = File(tmpDir, SnowLumaScripts.PROGRESS_FILE.removePrefix("/tmp/"))
    val installProgressDescriptionFile: File = File(tmpDir, SnowLumaScripts.PROGRESS_DESCRIPTION_FILE.removePrefix("/tmp/"))

    /** SnowLuma 本体装好了（完成标记、程序和 OneBot 配置都在），可以启动；不管 MaiBot 侧的适配器。 */
    fun isSnowLumaReady(): Boolean = readyMarker.isFile && entry.isFile && globalOneBotConfig.isFile

    /** SnowLuma 本体和 SnowLuma 适配器都装好、适配器配置也迁移好了才算装好，消息平台页按这个显示。 */
    fun isInstalled(): Boolean = isSnowLumaReady() && adapter.isAdapterConfigured()

    /** 第一次安装时生成的 WebUI 密码，读不到返回 null。 */
    fun readWebUiPassword(): String? =
        webUiPasswordFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** 启动脚本记下的 WebUI 端口，读不到（还没启动过）返回 null。 */
    fun readWebUiPort(): Int? = webUiPortFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()

    /** Xvfb 现在用的显示号，读不到返回 null。 */
    fun readDisplay(): Int? = displayFile.takeIf { it.isFile }?.readText()?.filter(Char::isDigit)?.toIntOrNull()

    private fun inRootfs(containerPath: String): File = File(rootfsDir, containerPath.removePrefix("/"))
}
