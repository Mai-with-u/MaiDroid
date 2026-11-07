package org.maiwithu.maidroid.platform.qq

import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.writeTextAtomically
import java.io.File

/**
 * 在容器里登录个人 QQ 的协议端：NapCat（platform/napcat）或 SnowLuma 客户端（platform/snowluma）。
 *
 * 两种都是在容器里跑 LinuxQQ，再给 MaiBot 开一个 OneBot WebSocket 服务端（同一个地址、端口和 token，见 QqOneBot），
 * MaiBot 侧都用同一个 SnowLuma 适配器去连，适配器连上后自己认出对端是哪种。
 * 它们共用 /opt/QQ 和 QQ 的登录数据，也抢同一个端口，所以同一时刻只能启用一种，启用哪种记在 QqClientChoice。
 *
 * label 是界面和终端日志里说的名字，logPrefix 是终端日志里这一路输出的前缀，wireValue 是记在文件里的写法。
 */
internal enum class QqClient(val label: String, val logPrefix: String, val wireValue: String) {
    NapCat("NapCat", "[NapCat]", "napcat"),
    SnowLuma("SnowLuma", "[SnowLuma]", "snowluma");

    companion object {
        /** 读记录文件的内容；没有记录或认不出时按 NapCat：只有 NapCat 的老版本没写过这个文件。 */
        fun fromWireValue(value: String?): QqClient =
            entries.firstOrNull { it.wireValue == value?.trim() } ?: NapCat
    }
}

/**
 * 现在启用哪种 QQ 协议端，记在容器的 /root/.maidroid/qq_client 里。
 * 前台服务拉起协议端时两个监管器各自看它，只有被启用的那个会启动；装好或切换协议端时由 QqClientSwitcher 改写。
 * 放在容器里而不是应用设置里：它说的是容器里装了什么、该跑什么，和 napcat_ready 这类标记放在一起。
 */
internal class QqClientChoice(paths: RuntimePaths) {
    private val file: File = File(paths.rootfsDir, "root/.maidroid/qq_client")

    fun read(): QqClient = QqClient.fromWireValue(runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull())

    fun isActive(client: QqClient): Boolean = read() == client

    /** 原子写入：写到一半断电不会留下半截内容（半截内容会被当成 NapCat）。 */
    fun write(client: QqClient) {
        file.parentFile?.mkdirs()
        file.writeTextAtomically(client.wireValue + "\n")
    }
}
