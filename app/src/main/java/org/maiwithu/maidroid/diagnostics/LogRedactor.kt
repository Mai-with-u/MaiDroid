package org.maiwithu.maidroid.diagnostics

import org.maiwithu.maidroid.runtime.ContainerDns

/**
 * 诊断包和复制出去的反馈文字在离开手机之前先打码，用户不用自己一行行找令牌和 QQ 号。分两层：
 * 1. MaiDroid 自己知道的具体值（MaiBot WebUI 令牌、NapCat 令牌、QQ 官方机器人 AppID/AppSecret、容器 SSH 密码、
 *    大模型 API Key、登录过的 QQ 号）原样换成“<已隐藏:名字>”，比按格式猜准；
 * 2. 再按常见格式兜底：sk- 开头的密钥、Bearer 令牌、地址里的 token=、“token: …”“api_key = …”这类键值、
 *    QQ 号/群号字样后面的数字、手机号、公网 IP（局域网地址和容器用的公共 DNS 保留，排查网络要看）。
 * 聊天内容没法可靠地认出来，反馈弹窗里会提醒用户自己看一眼。
 */
internal class LogRedactor(secrets: List<Secret>) {
    /** 一个要隐藏的具体值；label 写进替换后的占位符，看得出被藏掉的是什么。 */
    data class Secret(val label: String, val value: String)

    // 太短的值（空串、个位数）原样替换会误伤正文，跳过；长的先替换，免得短值先把长值拆开。
    private val exactSecrets = secrets
        .map { it.copy(value = it.value.trim()) }
        .filter { it.value.length >= MIN_SECRET_LENGTH }
        .distinctBy { it.value }
        .sortedByDescending { it.value.length }

    fun redact(text: String): String {
        var result = text
        for (secret in exactSecrets) {
            result = if (secret.value.all(Char::isDigit)) {
                // 纯数字（QQ 号）只换完整的一串，不动更长数字里碰巧相同的一段
                Regex("(?<!\\d)${Regex.escape(secret.value)}(?!\\d)").replace(result, "<已隐藏:${secret.label}>")
            } else {
                result.replace(secret.value, "<已隐藏:${secret.label}>")
            }
        }
        result = API_KEY.replace(result, "sk-<已隐藏>")
        result = BEARER.replace(result) { "${it.groupValues[1]}<已隐藏>" }
        result = URL_SECRET_PARAM.replace(result) { "${it.groupValues[1]}<已隐藏>" }
        result = KEY_VALUE_SECRET.replace(result) { "${it.groupValues[1]}<已隐藏>" }
        result = QQ_NUMBER.replace(result) { "${it.groupValues[1]}<QQ号>" }
        result = PHONE_NUMBER.replace(result, "<手机号>")
        result = IPV4.replace(result) { match -> if (isKeptAddress(match.value)) match.value else "<IP>" }
        return result
    }

    private companion object {
        const val MIN_SECRET_LENGTH = 4

        val API_KEY = Regex("""sk-[A-Za-z0-9_\-]{12,}""")
        val BEARER = Regex("""(?i)(Bearer\s+)[A-Za-z0-9._\-~+/=]{12,}""")
        // 值以 < 开头的是已经换好的占位符（或 MaiDroid 自己打的 <redacted>），不再动
        val URL_SECRET_PARAM = Regex("""(?i)([?&](?:token|access_token|key|secret|password)=)(?!<)[^&\s#"']+""")

        // “WebUI access token: abc123”“api_key = "xxx"”“"app_secret": "xxx"” 这类键值，值至少 6 个字符才算
        val KEY_VALUE_SECRET = Regex(
            """(?i)((?:api[_-]?key|access[_-]?token|app[_-]?secret|secret|password|token)["']?\s*[:=]\s*["']?)""" +
                """(?!<已隐藏)[^\s"',}&]{6,}"""
        )

        // QQ 号、群号、uin、user_id 等字样后面隔几个符号的 5~12 位数字
        val QQ_NUMBER = Regex("""(?i)((?:qq_account|user_id|group_id|self_id|uin|QQ号?|群号?|账号)[^\d\n]{0,4})\d{5,12}(?!\d)""")
        val PHONE_NUMBER = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
        val IPV4 = Regex("""(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])""")

        // 局域网、回环、链路本地、运营商级 NAT 地址和容器里写死的公共 DNS 不算隐私，排查网络时要看
        fun isKeptAddress(address: String): Boolean {
            val octets = address.split('.').map { it.toIntOrNull() ?: return true }
            if (octets.any { it > 255 }) return true
            val (a, b) = octets
            return a == 10 || a == 127 || a == 0 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) ||
                address in ContainerDns.PUBLIC_SERVERS
        }
    }
}
