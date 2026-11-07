package org.maiwithu.maidroid.platform.qq

/**
 * QQ 协议端（NapCat 或 SnowLuma）给 MaiBot 开的 OneBot WebSocket 服务端：地址、端口和访问 token 的位置。
 * 两种协议端用同一套，SnowLuma 适配器的 [client] 配置一次就够，换协议端不用改。
 */
internal object QqOneBot {
    const val HOST = "127.0.0.1"
    const val PORT = 8095

    /**
     * 容器内 OneBot 访问 token 的位置。文件名是只有 NapCat 时定的，SnowLuma 也读这一份：
     * 改名会让老用户的 token 和适配器配置对不上。
     */
    const val TOKEN_PATH = "/root/.maidroid/napcat_token"

    /**
     * 容器脚本片段：读出已保存的 token 放进 shell 变量 [variable]，没有就把 [token] 存下来用。
     * 只在第一次安装时生成，之后重装、换协议端都沿用，避免 MaiBot 适配器配置失配。
     */
    fun ensureTokenScript(token: String, variable: String = "onebot_token"): String =
        """
        mkdir -p /root/.maidroid
        if [ -s $TOKEN_PATH ]; then
            $variable="${'$'}(cat $TOKEN_PATH)"
        else
            $variable="$token"
            printf "%s" "${'$'}$variable" > $TOKEN_PATH
            chmod 600 $TOKEN_PATH 2>/dev/null || true
        fi
        """.trimIndent()
}
