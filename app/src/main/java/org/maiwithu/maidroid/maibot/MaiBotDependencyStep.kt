package org.maiwithu.maidroid.maibot

import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerScript

/**
 * 安装 MaiBot Python 依赖的三步，按声明顺序执行，任一步失败就停：
 * 装 uv -> 准备 Python 解释器 -> uv sync 装依赖包。
 * OOBE 和在线更新共用这份顺序和失败文案；三段脚本都可以重复执行，已经就绪的部分会自己跳过。
 */
internal enum class MaiBotDependencyStep(
    /** 这一步进行中时界面上的说明。 */
    val progressText: String,
    private val failureLabel: String
) {
    Uv("正在安装 uv...", "安装 uv 失败"),
    Python("正在准备 Python 解释器...", "准备 Python 解释器失败"),
    Packages("正在安装 Python 依赖包（uv sync），可能需要几分钟...", "安装 Python 依赖包失败");

    fun script(): ContainerScript = when (this) {
        Uv -> MaiBotScripts.installUv()
        Python -> MaiBotScripts.installPython()
        Packages -> MaiBotScripts.syncDependencies()
    }

    /**
     * 失败时给界面的一句话。前两步的脚本退出前会自己打印中文原因（最后一行）；
     * uv sync 的报错要从 uv 的输出里挑出“哪里失败、根本原因”，取不到再退回最后一行。
     */
    fun failureMessage(result: ContainerCommandResult): String {
        val reason = if (this == Packages) uvErrorSummary(result.output) else null
        return "$failureLabel：${reason ?: result.summary()}"
    }
}

/**
 * 从 uv 的输出里提炼失败原因：第一条 `error:` 或 `×` 开头的行说明哪里失败，
 * 最后一条 `cause:` 或 `╰─▶` 开头的行是根本原因，两者拼成“标题（原因）”。
 * uv 报错后常跟着 hint: 之类的提示，只取最后一行会丢掉真正的错误。没有报错行时返回 null。
 */
internal fun uvErrorSummary(output: String): String? {
    val lines = output.lineSequence().map { it.trim() }.toList()
    val headline = lines.firstNotNullOfOrNull { it.afterAnyPrefix(UV_ERROR_PREFIXES) }
        ?.trimEnd(':', '：')
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val cause = lines.mapNotNull { it.afterAnyPrefix(UV_CAUSE_PREFIXES) }
        .lastOrNull()
        ?.takeIf { it.isNotEmpty() && it != headline }
    return if (cause != null) "$headline（$cause）" else headline
}

// uv 的普通报错是 “error: …” 加若干 “cause: …”（旧版本写作 “Caused by: …”）；
// 构建、解析失败用树形格式 “× …” 加 “├─▶ / ╰─▶ …”。
private val UV_ERROR_PREFIXES = listOf("error:", "×")
private val UV_CAUSE_PREFIXES = listOf("cause:", "Caused by:", "╰─▶")

private fun String.afterAnyPrefix(prefixes: List<String>): String? =
    prefixes.firstOrNull { startsWith(it) }?.let { removePrefix(it).trim() }
