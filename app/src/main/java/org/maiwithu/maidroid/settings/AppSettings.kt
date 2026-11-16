package org.maiwithu.maidroid.settings

import android.util.Log
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ssh.ContainerSshPortRange

/**
 * 用 MMKV 持久化的应用设置：OOBE 是否完成及断点步骤、是否同意协议、自启开关、容器 SSH 配置、
 * 上次打开时的 MaiDroid 版本号、MaiBot 自动升级开关、是否安全退出、QQ 自定义安装包来源，以及竖屏悬浮胶囊停靠位置。
 * MMKV 写入即落盘，读写都很轻，可以在主线程直接访问。既有键名在用户设备上存在，不要随意改动。
 */
internal class AppSettings(
    private val kv: MMKV = MMKV.defaultMMKV()
) {

    private companion object {
        const val KEY_SETUP_COMPLETE = "setup_complete"
        const val KEY_AUTHORIZATION_ACCEPTED = "authorization_accepted"
        const val KEY_SERVICE_AUTO_START = "service_auto_start"
        const val KEY_CONTAINER_SSH_ENABLED = "container_ssh_enabled"
        const val KEY_CONTAINER_SSH_CONFIGURED = "container_ssh_configured"
        const val KEY_CONTAINER_SSH_PORT = "container_ssh_port"
        const val KEY_CONTAINER_SSH_ROOT_PASSWORD = "container_ssh_root_password"
        const val KEY_LAST_LAUNCHED_VERSION_CODE = "last_launched_version_code"
        const val KEY_MAIBOT_AUTO_UPDATE_DISABLED = "maibot_auto_update_disabled"
        const val KEY_USER_EXITED = "user_exited"
        const val KEY_OOBE_STEP = "oobe_step"
        const val KEY_NAPCAT_CUSTOM_SOURCE_URL = "napcat_custom_source_url"
        const val KEY_SNOWLUMA_CUSTOM_SOURCE_URL = "snowluma_custom_source_url"
        const val KEY_WEBUI_PILL_DOCK_RIGHT = "webui_pill_dock_right"
        const val KEY_WEBUI_PILL_VERTICAL_BIAS = "webui_pill_vertical_bias"

        const val DEFAULT_CONTAINER_SSH_PORT = 2222
        const val DEFAULT_WEBUI_PILL_VERTICAL_BIAS = 0.65f
    }

    /** OOBE 全部完成（MaiBot 装好且 WebUI 起来过），之后启动直接进主页。 */
    var setupComplete: Boolean
        get() = kv.decodeBool(KEY_SETUP_COMPLETE, false)
        set(value) {
            kv.encode(KEY_SETUP_COMPLETE, value)
        }

    /** 用户在首次启动的授权弹窗里同意了用户协议和隐私协议。 */
    var authorizationAccepted: Boolean
        get() = kv.decodeBool(KEY_AUTHORIZATION_ACCEPTED, false)
        set(value) {
            kv.encode(KEY_AUTHORIZATION_ACCEPTED, value)
        }

    /** 开机或应用覆盖升级后是否自动拉起 MaiBot 前台服务。目前没有界面开关，默认开启。 */
    val autoStartService: Boolean
        get() = kv.decodeBool(KEY_SERVICE_AUTO_START, true)

    var containerSshEnabled: Boolean
        get() = kv.decodeBool(KEY_CONTAINER_SSH_ENABLED, false)
        set(value) {
            kv.encode(KEY_CONTAINER_SSH_ENABLED, value)
        }

    /** 保存过端口和 root 密码；没配置过时不允许开启 SSH。 */
    val containerSshConfigured: Boolean
        get() = kv.decodeBool(KEY_CONTAINER_SSH_CONFIGURED, false)

    val containerSshPort: Int
        get() = kv.decodeInt(KEY_CONTAINER_SSH_PORT, DEFAULT_CONTAINER_SSH_PORT).coerceIn(ContainerSshPortRange)

    val containerSshRootPassword: String
        get() = kv.decodeString(KEY_CONTAINER_SSH_ROOT_PASSWORD, "") ?: ""

    /**
     * 上一次打开主界面时 MaiDroid 的版本号（versionCode）；比当前小说明 MaiDroid 刚更新过。
     * 记录这个值之前的旧版本升上来时读到 0，同样算刚更新过。
     */
    var lastLaunchedVersionCode: Int
        get() = kv.decodeInt(KEY_LAST_LAUNCHED_VERSION_CODE, 0)
        set(value) {
            kv.encode(KEY_LAST_LAUNCHED_VERSION_CODE, value)
        }

    /**
     * 用户在设置页“应用版本”弹窗底部打开了“关闭 MaiBot 自动更新”：MaiDroid 更新后首次启动时不再自动升级 MaiBot。
     * 默认 false，即自动升级。只管这一次自动升级，启动时的 MaiBot 新版本提醒不受它影响。
     */
    var maiBotAutoUpdateDisabled: Boolean
        get() = kv.decodeBool(KEY_MAIBOT_AUTO_UPDATE_DISABLED, false)
        set(value) {
            kv.encode(KEY_MAIBOT_AUTO_UPDATE_DISABLED, value)
        }

    /**
     * 用户在设置页点了“安全退出”，之后还没再用过 MaiDroid。为 true 时开机不自动拉起麦麦（和强行停止应用后一样，
     * 等用户自己打开）；前台服务收到应用里发来的任何启动请求时清掉。
     */
    var userExited: Boolean
        get() = kv.decodeBool(KEY_USER_EXITED, false)
        set(value) {
            kv.encode(KEY_USER_EXITED, value)
        }

    /**
     * 首次引导走到了哪一步（OobeStep 的名字），没走过时为 null。应用被划掉、闪退、强制重启、断电后重新打开，
     * 直接回到这一步接着装，不用再从权限页点过来；以前只存在界面的保存状态里，这些情况下都会丢。
     */
    var oobeStep: String?
        get() = kv.decodeString(KEY_OOBE_STEP, null)
        set(value) {
            kv.encode(KEY_OOBE_STEP, value)
        }

    /**
     * 用户自己填写的 NapCat 自定义仓库或压缩包直链；默认留空，不内置第三方 fork。
     * 未填时下载安装会弹窗让用户填写，已安装后可在 NapCat 配置弹窗里修改或重装。
     */
    var napCatCustomSourceUrl: String
        get() = kv.decodeString(KEY_NAPCAT_CUSTOM_SOURCE_URL, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_NAPCAT_CUSTOM_SOURCE_URL, value.trim())
        }

    /**
     * 用户自己填写的 SnowLuma 自定义仓库或发行包直链；默认留空，不内置第三方 fork。
     * 未填时下载安装会弹窗让用户填写，已安装后可在 SnowLuma 配置弹窗里修改或重装。
     */
    var snowLumaCustomSourceUrl: String
        get() = kv.decodeString(KEY_SNOWLUMA_CUSTOM_SOURCE_URL, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_SNOWLUMA_CUSTOM_SOURCE_URL, value.trim())
        }

    /** 竖屏 WebUI 侧边胶囊是否停靠在屏幕右侧；默认停在左侧（false），符合单手握持与滑屏习惯。 */
    var webuiPillDockRight: Boolean
        get() = kv.decodeBool(KEY_WEBUI_PILL_DOCK_RIGHT, false)
        set(value) {
            kv.encode(KEY_WEBUI_PILL_DOCK_RIGHT, value)
        }

    /** 竖屏 WebUI 侧边胶囊沿屏高的垂直停靠比例，限定在 [0f, 1f]。 */
    var webuiPillVerticalBias: Float
        get() = kv.decodeFloat(KEY_WEBUI_PILL_VERTICAL_BIAS, DEFAULT_WEBUI_PILL_VERTICAL_BIAS).coerceIn(0f, 1f)
        set(value) {
            kv.encode(KEY_WEBUI_PILL_VERTICAL_BIAS, value.coerceIn(0f, 1f))
        }

    fun saveContainerSsh(port: Int, rootPassword: String) {
        kv.encode(KEY_CONTAINER_SSH_PORT, port.coerceIn(ContainerSshPortRange))
        kv.encode(KEY_CONTAINER_SSH_ROOT_PASSWORD, rootPassword)
        kv.encode(KEY_CONTAINER_SSH_CONFIGURED, true)
    }
}

/**
 * MMKV 的文件校验不过时怎么办。断电、存储满时设置文件可能只写进去一半，MMKV 默认的做法是整个丢掉，
 * 用户就得重新同意协议、重走一遍首次引导；改成尽量恢复：能读出来的设置都留下，读不出的那几项才丢。
 * 要在第一次读设置之前注册（MaiDroidApplication 里紧跟 MMKV.initialize）。
 */
internal object AppSettingsRecovery : MMKVHandler {
    override fun onMMKVCRCCheckFail(mmapID: String): MMKVRecoverStrategic = recover(mmapID, "校验")

    override fun onMMKVFileLengthError(mmapID: String): MMKVRecoverStrategic = recover(mmapID, "长度")

    override fun wantLogRedirecting(): Boolean = false

    override fun mmkvLog(level: MMKVLogLevel, file: String, line: Int, function: String, message: String) = Unit

    private fun recover(mmapID: String, what: String): MMKVRecoverStrategic {
        Log.w("MaiDroid", "MMKV $mmapID $what check failed, recovering")
        TerminalLog.append("[MaiDroid] 设置文件（$mmapID）${what}没通过，可能是上次断电或存储满了，已尽量恢复")
        return MMKVRecoverStrategic.OnErrorRecover
    }
}
