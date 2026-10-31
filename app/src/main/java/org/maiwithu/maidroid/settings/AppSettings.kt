package org.maiwithu.maidroid.settings

import com.tencent.mmkv.MMKV
import org.maiwithu.maidroid.ssh.ContainerSshPortRange

/**
 * 用 MMKV 持久化的应用设置：OOBE 是否完成、是否同意协议、开机自启、容器 SSH 配置、MaiBot 自动更新。
 * MMKV 写入即落盘，读写都很轻，可以在主线程直接访问。键名已在用户设备上存在，不能改。
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

        const val DEFAULT_CONTAINER_SSH_PORT = 2222
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

    /** 开机后是否自动拉起 MaiBot。目前没有界面开关，默认开启。 */
    val autoStartOnBoot: Boolean
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

    /** 用户在设置页打开了“关闭 MaiBot 自动更新”：MaiDroid 更新后首次启动时不再自动升级 MaiBot。默认关（即自动更新）。 */
    var maiBotAutoUpdateDisabled: Boolean
        get() = kv.decodeBool(KEY_MAIBOT_AUTO_UPDATE_DISABLED, false)
        set(value) {
            kv.encode(KEY_MAIBOT_AUTO_UPDATE_DISABLED, value)
        }

    fun saveContainerSsh(port: Int, rootPassword: String) {
        kv.encode(KEY_CONTAINER_SSH_PORT, port.coerceIn(ContainerSshPortRange))
        kv.encode(KEY_CONTAINER_SSH_ROOT_PASSWORD, rootPassword)
        kv.encode(KEY_CONTAINER_SSH_CONFIGURED, true)
    }
}
