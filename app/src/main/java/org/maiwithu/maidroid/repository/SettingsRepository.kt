package org.maiwithu.maidroid.repository

import com.tencent.mmkv.MMKV

/**
 * 用 MMKV 持久化的应用设置：OOBE 是否完成、是否同意协议、开机自启、容器 SSH 配置。
 */
class SettingsRepository(
    private val kv: MMKV = MMKV.defaultMMKV()
) {

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_AUTHORIZATION_ACCEPTED = "authorization_accepted"
        private const val KEY_SERVICE_AUTO_START = "service_auto_start"
        private const val KEY_CONTAINER_SSH_ENABLED = "container_ssh_enabled"
        private const val KEY_CONTAINER_SSH_CONFIGURED = "container_ssh_configured"
        private const val KEY_CONTAINER_SSH_PORT = "container_ssh_port"
        private const val KEY_CONTAINER_SSH_ROOT_PASSWORD = "container_ssh_root_password"

        private const val DEFAULT_CONTAINER_SSH_PORT = 2222
    }

    // ── Setup State ──────────────────────────────────────────────────

    fun isSetupComplete(): Boolean = kv.decodeBool(KEY_SETUP_COMPLETE, false)
    fun setSetupComplete(complete: Boolean) = kv.encode(KEY_SETUP_COMPLETE, complete)

    fun isAuthorizationAccepted(): Boolean = kv.decodeBool(KEY_AUTHORIZATION_ACCEPTED, false)
    fun setAuthorizationAccepted(accepted: Boolean) = kv.encode(KEY_AUTHORIZATION_ACCEPTED, accepted)

    // ── Service Auto-Start ───────────────────────────────────────────

    fun isServiceAutoStart(): Boolean = kv.decodeBool(KEY_SERVICE_AUTO_START, true)
    fun setServiceAutoStart(autoStart: Boolean) = kv.encode(KEY_SERVICE_AUTO_START, autoStart)

    // Container SSH

    fun isContainerSshEnabled(): Boolean = kv.decodeBool(KEY_CONTAINER_SSH_ENABLED, false)
    fun setContainerSshEnabled(enabled: Boolean) = kv.encode(KEY_CONTAINER_SSH_ENABLED, enabled)

    fun isContainerSshConfigured(): Boolean = kv.decodeBool(KEY_CONTAINER_SSH_CONFIGURED, false)

    fun getContainerSshPort(): Int =
        kv.decodeInt(KEY_CONTAINER_SSH_PORT, DEFAULT_CONTAINER_SSH_PORT)
            .coerceIn(1024, 65535)

    fun getContainerSshRootPassword(): String =
        kv.decodeString(KEY_CONTAINER_SSH_ROOT_PASSWORD, "") ?: ""

    fun setContainerSshConfig(port: Int, rootPassword: String) {
        kv.encode(KEY_CONTAINER_SSH_PORT, port.coerceIn(1024, 65535))
        kv.encode(KEY_CONTAINER_SSH_ROOT_PASSWORD, rootPassword)
        kv.encode(KEY_CONTAINER_SSH_CONFIGURED, true)
    }
}
