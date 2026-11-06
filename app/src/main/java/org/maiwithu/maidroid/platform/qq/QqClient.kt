package org.maiwithu.maidroid.platform.qq

/**
 * 在容器里登录个人 QQ 的协议端：NapCat（platform/napcat）或 SnowLuma 客户端（platform/snowluma）。
 *
 * 两种都是在容器里跑 LinuxQQ，再给 MaiBot 开一个 OneBot WebSocket 服务端（同一个地址、端口和 token，见 QqOneBot），
 * MaiBot 侧都用同一个 SnowLuma 适配器去连，适配器连上后自己认出对端是哪种。
 * 它们共用 /opt/QQ 和 QQ 的登录数据，也抢同一个端口，所以同一时刻只能启用一种，启用哪种记在 QqClientChoice。
 *
 * label 是界面和终端日志里说的名字，logPrefix 是终端日志里这一路输出的前缀。
 */
internal enum class QqClient(val label: String, val logPrefix: String) {
    NapCat("NapCat", "[NapCat]"),
    SnowLuma("SnowLuma", "[SnowLuma]")
}
