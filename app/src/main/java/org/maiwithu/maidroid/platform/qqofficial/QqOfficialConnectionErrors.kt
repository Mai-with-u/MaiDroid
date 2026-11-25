package org.maiwithu.maidroid.platform.qqofficial

import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor

// 适配器连上 QQ 开放平台网关后不打任何日志，只在失败时打一行报错，
// 所以“是否在线”只能从终端日志里有没有近期报错来推断，这个文件负责从日志行里认出这些报错。
// 传进来的行已经是纯文本：TerminalLog.lines 广播的就是纯文本，读历史时 QqOfficialPlatformManager 先用 AnsiText.plain 转过。

private const val QqOfficialErrorMaxLength = 160
private val fatal4914Regex = Regex("""\b4914\b""")
private val fatal4915Regex = Regex("""\b4915\b""")
private val sessionTimeout4009Regex = Regex("""code\s*=\s*4009(?!\d)""")

internal const val QqOfficialFrequentTimeoutHint =
    "近 10 分钟内多次出现连接超时（4009），插件会自动重连。请确认同一 AppID 未在其他设备同时登录，" +
        "并检查网络稳定性与系统省电限制；若仅在息屏后容易掉线，也可在设置页开启「Wi-Fi 低延迟保活」。"

/** 从适配器日志解析出的连接失败：persistent 为 true 表示适配器已永久停止重试，不应按 60 秒自动清零。 */
internal data class QqOfficialConnectionFailure(
    val message: String,
    val persistent: Boolean
)

/**
 * 从一行 MaiBot 日志里认出 QQ 官方适配器的连接错误，返回给用户看的说明；
 * 与适配器无关、或只是“心跳未确认、重新连接”这类会自愈的提示时返回 null。
 *
 * 适配器连上网关后不打任何日志，只在失败时每隔几秒重试并打一行
 * “QQ 官方网关连接失败，将重试: 原因”，若遇 4914/4915 则永久停止重试。
 */
internal fun parseQqOfficialConnectionError(line: String): QqOfficialConnectionFailure? = when {
    "QQ 官方适配器缺少凭据" in line -> QqOfficialConnectionFailure(
        message = "未填写 AppID 或 AppSecret",
        persistent = true
    )
    "QQ 官方网关不可恢复地断开" in line -> QqOfficialConnectionFailure(
        message = describeFatalQqOfficialDisconnect(line).take(QqOfficialErrorMaxLength),
        persistent = true
    )
    // 内置 qq_official_adapter 对 HTTP 状态异常单打一行“QQ 官方网关 HTTP 请求失败，将重试: ...”，需同步识别。
    "QQ 官方网关连接失败" in line || "QQ 官方网关 HTTP 请求失败" in line -> QqOfficialConnectionFailure(
        message = describeQqOfficialFailure(
            line.substringAfter("将重试:", missingDelimiterValue = "").trim()
        ).take(QqOfficialErrorMaxLength),
        persistent = false
    )
    else -> null
}

/** 从一行 MaiBot 日志里提取 QQ 官方适配器的失败原因文本（兼容既有调用方与单测）。 */
internal fun qqOfficialConnectionError(line: String): String? =
    parseQqOfficialConnectionError(line)?.message

/** 识别网关返回 4009（Session timed out）导致的断连重试日志；正常约半小时轮换一次，短时高频才提示排查保活。 */
internal fun isQqOfficialSessionTimeoutDisconnect(line: String): Boolean =
    "QQ 官方网关已断开" in line && sessionTimeout4009Regex.containsMatchIn(line)

/**
 * 统计近期 4009 会话超时掉线频率的滑动时间窗口，用于区分正常的半小时网关轮换与移动端网络/休眠异常。
 */
internal class QqOfficialTimeoutWindow(
    private val windowMs: Long = QqOfficialTimeoutWindowMs,
    private val threshold: Int = QqOfficialFrequentTimeoutThreshold
) {
    private val timestamps = ArrayDeque<Long>()

    /** 记录一次 4009 事件并清理窗口外旧记录，达到频繁阈值时返回 true。 */
    fun record(nowMs: Long): Boolean {
        prune(nowMs)
        timestamps.addLast(nowMs)
        return timestamps.size >= threshold
    }

    /** 检查当前时间窗口内的 4009 次数是否仍达到频繁阈值。 */
    fun isFrequent(nowMs: Long): Boolean {
        prune(nowMs)
        return timestamps.size >= threshold
    }

    /** 清空计数窗口，供重启 MaiBot 或重置配置时复位提示。 */
    fun clear() {
        timestamps.clear()
    }

    private fun prune(nowMs: Long) {
        while (timestamps.isNotEmpty() && nowMs - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
    }
}

private fun describeFatalQqOfficialDisconnect(line: String): String = when {
    fatal4914Regex.containsMatchIn(line) ->
        "机器人已下架（4914），当前仅允许沙箱环境连接，请在 QQ 开放平台检查上线状态"
    fatal4915Regex.containsMatchIn(line) ->
        "机器人已被封禁（4915），无法连接 QQ 开放平台，请前往平台查看封禁与权限状态"
    else ->
        "QQ 官方网关已不可恢复地断开，请检查机器人上线状态及平台权限"
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
