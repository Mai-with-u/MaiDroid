package org.maiwithu.maidroid.platform.qqofficial

import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor

// 适配器连上 QQ 开放平台网关后不打任何日志，只在失败时打一行报错，
// 所以“是否在线”只能从终端日志里有没有近期报错来推断，这个文件负责从日志行里认出这些报错。
// 传进来的行已经是纯文本：TerminalLog.lines 广播的就是纯文本，读历史时 QqOfficialPlatformManager 先用 AnsiText.plain 转过。

private const val QqOfficialErrorMaxLength = 160

/**
 * 从一行 MaiBot 日志里认出 QQ 官方适配器的连接错误，返回给用户看的说明；
 * 与适配器无关、或只是“心跳未确认、重新连接”这类会自愈的提示时返回 null。
 *
 * 适配器连上网关后不打任何日志，只在失败时每隔几秒重试并打一行
 * “QQ 官方网关连接失败，将重试: 原因”，所以这里只能识别失败，不能识别成功。
 */
internal fun qqOfficialConnectionError(line: String): String? {
    val message = when {
        "QQ 官方适配器缺少凭据" in line -> "未填写 AppID 或 AppSecret"
        "QQ 官方网关连接失败" in line -> describeQqOfficialFailure(
            line.substringAfter("将重试:", missingDelimiterValue = "").trim()
        )
        else -> return null
    }
    return message.take(QqOfficialErrorMaxLength)
}

private fun describeQqOfficialFailure(reason: String): String = when {
    // 11298：接口访问源 IP 不在白名单。手机网络的出口 IP 会变，白名单留空才不会被拦。
    "11298" in reason || "白名单" in reason ->
        "当前网络的 IP 不在机器人的 IP 白名单内，请在 QQ 开放平台清空 IP 白名单"
    "获取 QQ 访问凭证失败" in reason ->
        "获取访问凭证失败，请检查 AppID 和 AppSecret（${reason.substringAfter("获取 QQ 访问凭证失败:").trim()}）"
    reason.isBlank() -> "连接 QQ 开放平台失败"
    else -> "连接 QQ 开放平台失败：$reason"
}

/** MaiBot 进程重新拉起时 MaiBotProcessSupervisor 打的行；之前的连接报错都属于上一轮，不再作数。 */
internal fun isMaiBotStartLine(line: String): Boolean =
    line.startsWith(MaiBotProcessSupervisor.PROCESS_STARTED_LOG)
