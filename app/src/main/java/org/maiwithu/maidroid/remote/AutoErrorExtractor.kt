package org.maiwithu.maidroid.remote

import org.maiwithu.maidroid.diagnostics.AUTO_FIX_LOG_PREFIX
import org.maiwithu.maidroid.diagnostics.FAILURE_LOG_PREFIX
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.LogRedactor
import java.security.MessageDigest

/** 自动报错的三类上报通道，与服务端 error_issues.category 字段一一对应。 */
internal enum class AutoErrorCategory(val wireValue: String) {
    Crash("crash"),
    Install("install"),
    MaiBotBoot("maibot_boot")
}

/** 从本地报错现场提炼出的脱敏自动上报草稿，包含稳定聚合指纹与防隐私泄漏的上下文片段。 */
internal data class AutoErrorDraft(
    val category: AutoErrorCategory,
    val errorCode: String,
    val fingerprint: String,
    val title: String,
    val message: String,
    val contextText: String
)

/**
 * 自动报错准入分类、隐私清洗与聚合指纹生成器：
 * 1. App 闪退 / ANR：只留头信息与 Java/Kotlin 异常栈，遇到“闪退前的终端输出”标记立即截断丢弃；
 * 2. 安装 / 容器 / 更新 / 协议端阶段：过滤纯网络抖动等噪音后，提取脱敏尾部 50 行终端报错；
 * 3. MaiBot 启动失败：仅通过严格白名单提取纯 Python Traceback 与启动异常行，绝不放行常规业务与聊天日志。
 */
internal object AutoErrorExtractor {

    private const val CRASH_TERMINAL_MARKER = "----- 闪退前的终端输出 -----"
    private const val MAX_CRASH_CONTEXT_CHARS = 12_000
    private const val MAX_INSTALL_TAIL_LINES = 50
    private const val MAX_CONTEXT_CHARS = 10_000
    private const val MAX_TRACEBACK_LINES = 45
    private const val MAX_BOOT_WHITELIST_LINES = 25
    private const val MAX_LINE_CHARS = 280
    private const val MAX_TITLE_CHARS = 140
    private const val MAX_MESSAGE_CHARS = 260

    // 安装/容器阶段的纯用户环境或瞬态抖动原因只保留枚举计数，不传终端现场以免制造噪音
    private val INSTALL_NOISE_CAUSES = setOf(
        FailureCause.NoNetwork,
        FailureCause.Dns,
        FailureCause.Timeout,
        FailureCause.ConnectionLost,
        FailureCause.Tls,
        FailureCause.ClockSkew,
        FailureCause.DiskFull,
        FailureCause.LowBattery,
        FailureCause.PortInUse,
        FailureCause.AptMirrorSyncing,
        FailureCause.GitLocked
    )

    private val MAIBOT_NOISE_CAUSES = setOf(
        FailureCause.LowBattery,
        FailureCause.PortInUse,
        FailureCause.DiskFull
    )

    // 明显属于聊天消息流或正常日常运行的关键词，在自动提取上下文时直接剔除
    private val CHAT_PRIVACY_KEYWORDS = listOf(
        "收到消息", "发送消息", "回复消息", "私聊", "群聊", "聊天记录",
        "chat_stream", "recv_msg", "send_msg", "message_content", "group_chat", "private_chat"
    )

    private val INSTALL_ERROR_KEYWORDS = listOf(
        "error", "fatal", "failed", "exception", "cannot", "permission denied",
        "exit code", "not found", "returned error", "no such file", "unable to",
        "segmentation fault", "killed", "exec format", "traceback",
        "安装失败", "配置失败", "退出码", "需要 maibot", "还没生成", "不完整", "失败：", "失败:"
    )

    // QQ 适配器配置等待期只放行插件加载/依赖相关的 MaiBot 输出，剔除图片嵌入、聊天推理等无关业务异常
    private val ADAPTER_RELEVANT_MAIBOT_KEYWORDS = listOf(
        "snowluma", "adapter", "manifest", "插件", "silk-python", "低于最小要求"
    )

    private val ADAPTER_CONFIG_STEPS = setOf(
        FailureStep.NapCatAdapterConfig,
        FailureStep.SnowLumaAdapterConfig
    )

    // 识别终端里的 NapCat 字符画二维码、扫码提示噪音以及扫码风控失败行，避免占满 50 行上报窗口或误充当配置失败标题
    private val QR_ASCII_ART_LINE = Regex("""^(?:\[[^\]]+]\s*)?[█▄▀ ]*[▄▀][█▄▀ ]{7,}$""")

    private val NAPCAT_QR_NOISE_KEYWORDS = listOf(
        "二维码解码url",
        "如果控制台二维码无法扫码",
        "请扫描下面的二维码",
        "二维码已保存到",
        "请求刷新二维码",
        "二维码已被扫描"
    )

    private val NAPCAT_LOGIN_ERROR_RE = Regex("""\[Core]\s*\[Login]\s*Login Error""", RegexOption.IGNORE_CASE)

    // 过滤容器内 Electron/QQ 必刷的无害调试与日志指纹行，避免挤占窗口并按随机哈希分裂错误指纹
    private val PURE_HEX64_LINE = Regex("""^(?:\[[^\]]+]\s*)?[0-9a-f]{64}$""", RegexOption.IGNORE_CASE)

    private val BENIGN_RUNTIME_NOISE_MARKERS = listOf(
        "error:dbus/",
        "file_path_watcher_inotify",
        "[nativecrashhandler.cpp]",
        "[buglymanager.cpp]",
        "[qq hotupdate]",
        "droppedframe("
    )

    // dpkg 安装正常解包/配置包列表的进度行，避免包名本身带 "error"（如 liberror-perl）抢占错误标题
    private val DPKG_PROGRESS_PREFIXES = listOf(
        "unpacking ",
        "preparing to unpack ",
        "selecting previously unselected package",
        "setting up "
    )

    private val CRASH_HEADER_VERSION_RE = Regex("""^MaiDroid\s+([0-9A-Za-z._-]+)""")

    // 正常退出码（如 apt/git 成功输出 exit code: 0）不得因匹配到 "exit code" 关键词而被当成错误标题
    private val ZERO_EXIT_CODE_RE = Regex("""(?i)(?:exit code|退出码)\s*[:：]?\s*0\b""")

    private val EXCEPTION_CLASS_LINE =
        Regex("""^([A-Za-z_][A-Za-z0-9_.]*(?:Error|Exception|Exit|Interrupt)|SyntaxError|IndentationError|MemoryError|SystemExit|TOMLDecodeError|ModuleNotFoundError|ImportError)\b.*""")

    private val PYTHON_FILE_FRAME =
        Regex("""^\s*File\s+"([^"]+)",\s*line\s+\d+,\s*in\s+(\S+)""")

    private val LOG_TIMESTAMP_LINE =
        Regex("""^(?:\d{2,4}[-/]\d{2}[-/]\d{2}|\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}|\[\d{2}:\d{2}:\d{2})""")

    // 协议端互斥提示（另一侧正在安装）和自定义源表单输入校验属于用户操作拦截，不应污染远端 ops 报错看板
    private val USER_GUARDRAIL_PRECHECK_MARKERS = listOf(
        "正在安装，等它装完再装",
        "请填写",
        "地址不能包含",
        "请输入合法的",
        "地址必须以",
        "地址缺少域名",
        "地址中请勿包含",
        "仓库地址不完整",
        "需要填写仓库地址",
        "不能使用 .zip 包",
        "直链需指向",
        "直接下载地址"
    )

    private val MAIBOT_BOOT_WHITELIST_REGEXES = listOf(
        Regex("""\[MaiBot]\s+(?:Process started|Process exited with code:|Failed to start process:|Runtime validation failed:)"""),
        // 放行被系统 SIGKILL 强杀时的本轮运行时长与子进程水位日志，便于现场分辨冷启动崩溃与后台长跑被杀
        Regex("""\[MaiDroid]\s+容器进程收到\s+SIGKILL\(137\)"""),
        // 收录 1.4.x 宿主无 Traceback 直接以退出码 2 退出的硬报错行（仅限宿主引导/系统动态库前缀，不放行聊天日志）
        Regex("""(?:宿主启动失败|MaiBot 宿主关闭未完成|WebUI 凭据文件|WebUI 前端资源不可用)[：:]"""),
        Regex("""(?:error while loading shared libraries|cannot open shared object file|Permission denied)"""),
        Regex("""^(?:/bin/sh:|sh:|bash:|uv:|python[0-9.]*:)\s+"""),
        Regex("""proot\s+(?:error|warning):""", RegexOption.IGNORE_CASE),
        Regex("""(?:Exec format error|cannot execute binary file|Segmentation fault|Illegal instruction|^\s*Killed\s*$)"""),
        Regex("""(?:TOMLDecodeError|tomlkit\.exceptions|Invalid TOML|ModuleNotFoundError|No module named|ImportError)"""),
        Regex("""(?:当前数据库版本高于代码内注册的最新迁移版本|database is locked|Address already in use|\[Errno \d+])"""),
        EXCEPTION_CLASS_LINE
    )

    private val TIMESTAMP_RE = Regex("""\b(?:\d{4}-\d{2}-\d{2}[T\s])?\d{2}:\d{2}:\d{2}(?:[.,]\d+)?\b""")
    private val DATE_RE = Regex("""\b\d{2,4}[-/]\d{2}[-/]\d{2}\b""")
    private val HEX_RE = Regex("""0x[0-9a-fA-F]+""")
    private val TEMP_PATH_RE = Regex("""/tmp/[A-Za-z0-9._-]+""")
    private val STACK_LINE_NUM_RE = Regex(""":\d+\)""")
    private val PID_PORT_RE = Regex("""(?i)\b(pid|port)\s*[:=]?\s*\d+\b""")
    private val MULTI_DIGITS_RE = Regex("""\b\d{2,}\b""")
    private val WHITESPACE_RE = Regex("""\s+""")

    /** 判断某类报错是否需要自动携带脱敏现场上报；返回 null 时仅做原有计数统计。 */
    fun classifyForAutoReport(
        step: FailureStep,
        cause: FailureCause?,
        message: String = ""
    ): AutoErrorCategory? = when (step) {
        FailureStep.AppCrash -> AutoErrorCategory.Crash
        FailureStep.MaiBotBlocked,
        FailureStep.MaiBotCrash,
        FailureStep.MaiBotNotStarted,
        FailureStep.MaiBotTimeout ->
            if (cause in MAIBOT_NOISE_CAUSES) null else AutoErrorCategory.MaiBotBoot
        // QQ 官方机器人连接期已处于消息收发运行期，保守拦截自动上下文上报
        FailureStep.QqOfficialConnect -> null
        FailureStep.NapCatPrecheck,
        FailureStep.SnowLumaPrecheck ->
            if (cause in INSTALL_NOISE_CAUSES || (cause == null && isUserGuardrailPrecheckMessage(message))) {
                null
            } else {
                AutoErrorCategory.Install
            }
        else ->
            if (cause in INSTALL_NOISE_CAUSES) null else AutoErrorCategory.Install
    }

    /** 将 `UV-SYNC/NET-DNS` 这样的组合错误码还原回步骤和原因枚举。 */
    fun parseFailureCode(code: String): Pair<FailureStep, FailureCause?>? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        val stepCode = trimmed.substringBefore('/')
        val causeCode = trimmed.substringAfter('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
        val step = FailureStep.entries.firstOrNull { it.code == stepCode } ?: return null
        val cause = causeCode?.let { target -> FailureCause.entries.firstOrNull { it.code == target } }
        return step to cause
    }

    /** 从本地崩溃文件第一行表头提取崩溃发生时的应用版本号，避免升级后仍按当前新版本重复上报旧版本残留崩溃。 */
    fun extractCrashHeaderVersion(crashFileText: String?): String? {
        val firstLine = crashFileText
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return null
        return CRASH_HEADER_VERSION_RE.find(firstLine)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
    }

    /** 构造 App 闪退/原生崩溃/ANR 自动上报草稿，彻底剥离闪退前的终端日志。 */
    fun buildCrashDraft(
        crashSummary: String,
        crashFileText: String?,
        redactor: LogRedactor
    ): AutoErrorDraft {
        val cleanSummary = redactor.redact(crashSummary.trim().ifBlank { "未知异常" }).take(MAX_MESSAGE_CHARS)
        if (crashFileText.isNullOrBlank()) {
            val sig = normalizeSignatureLine(cleanSummary)
            return AutoErrorDraft(
                category = AutoErrorCategory.Crash,
                errorCode = FailureStep.AppCrash.code,
                fingerprint = sha256Hex("crash|${FailureStep.AppCrash.code}|native|$sig").take(32),
                title = "系统异常退出：$cleanSummary".take(MAX_TITLE_CHARS),
                message = "MaiDroid 上次闪退：$cleanSummary".take(MAX_MESSAGE_CHARS),
                contextText = "系统进程异常退出记录：$cleanSummary"
            )
        }

        // 先截断闪退前终端输出标记，确保不会把闪退瞬间的聊天或业务输出带出手机
        val stackSection = crashFileText.substringBefore(CRASH_TERMINAL_MARKER).trimEnd()
        val redactedStack = redactor.redact(stackSection).take(MAX_CRASH_CONTEXT_CHARS)
        val stackLines = redactedStack.lines()

        val exceptionLine = stackLines.firstOrNull { line ->
            val t = line.trim()
            t.isNotEmpty() &&
                !t.startsWith("MaiDroid ") &&
                !t.startsWith("时间：") &&
                !t.startsWith("线程：") &&
                !t.startsWith("at ") &&
                !t.startsWith("Caused by:") &&
                !t.startsWith("...")
        } ?: cleanSummary
        val exceptionClass = exceptionLine.substringBefore(':').trim().ifBlank { cleanSummary }

        // 优先抓第一个业务自身栈帧作为指纹定位点，去掉源文件名后的具体行号，避免小补丁改行分裂聚合结果
        val appFrame = stackLines.firstOrNull { it.trimStart().startsWith("at org.maiwithu.maidroid.") }
        val anyFrame = appFrame ?: stackLines.firstOrNull { it.trimStart().startsWith("at ") }
        val normalizedFrame = anyFrame?.trim()?.replace(STACK_LINE_NUM_RE, ")").orEmpty()

        val fingerprint = sha256Hex(
            "crash|${FailureStep.AppCrash.code}|$exceptionClass|$normalizedFrame"
        ).take(32)

        return AutoErrorDraft(
            category = AutoErrorCategory.Crash,
            errorCode = FailureStep.AppCrash.code,
            fingerprint = fingerprint,
            title = exceptionLine.take(MAX_TITLE_CHARS),
            message = "MaiDroid 上次闪退：$cleanSummary".take(MAX_MESSAGE_CHARS),
            contextText = redactedStack
        )
    }

    /** 构造 OOBE/容器/依赖/协议端安装与更新阶段的脱敏自动上报草稿。 */
    fun buildInstallDraft(
        step: FailureStep,
        cause: FailureCause?,
        message: String,
        terminalPlainLines: List<String>,
        setupComplete: Boolean,
        redactor: LogRedactor
    ): AutoErrorDraft {
        val errorCode = cause?.let { "${step.code}/${it.code}" } ?: step.code
        val redactedMessage = redactor.redact(message.trim().ifBlank { step.label }).take(MAX_MESSAGE_CHARS)

        val autoFixIdx = terminalPlainLines.indexOfLast { it.contains(AUTO_FIX_LOG_PREFIX) }
        val afterRetry = if (autoFixIdx >= 0 && autoFixIdx + 1 < terminalPlainLines.size) {
            terminalPlainLines.subList(autoFixIdx + 1, terminalPlainLines.size)
        } else {
            terminalPlainLines
        }

        val filtered = afterRetry
            .filter { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@filter false
                if (trimmed.contains(FAILURE_LOG_PREFIX)) return@filter false
                if (containsChatPrivacyKeyword(trimmed)) return@filter false
                if (isQqQrNoiseLine(trimmed)) return@filter false
                if (isBenignRuntimeNoiseLine(trimmed)) return@filter false
                if (!shouldKeepMaiBotLineInInstall(trimmed, step, setupComplete)) return@filter false
                true
            }
            .takeLast(MAX_INSTALL_TAIL_LINES)

        val contextText = if (filtered.isNotEmpty()) {
            trimTailToChars(redactor.redact(filtered.joinToString("\n")), MAX_CONTEXT_CHARS)
        } else {
            redactedMessage
        }

        val sigSourceLines = contextText.lines().map(String::trim).filter(String::isNotEmpty)
        val allowMaiBotSignal = step in ADAPTER_CONFIG_STEPS
        val highSignalLine = sigSourceLines.lastOrNull { line ->
            val lower = line.lowercase()
            val strippedLower = if (lower.startsWith("[")) lower.substringAfter("]", lower).trimStart() else lower
            !line.startsWith("[MaiDroid] ") &&
                (allowMaiBotSignal || !line.startsWith("[MaiBot]")) &&
                (!allowMaiBotSignal || !NAPCAT_LOGIN_ERROR_RE.containsMatchIn(line)) &&
                DPKG_PROGRESS_PREFIXES.none { strippedLower.startsWith(it) } &&
                !ZERO_EXIT_CODE_RE.containsMatchIn(line) &&
                INSTALL_ERROR_KEYWORDS.any { lower.contains(it) }
        } ?: redactedMessage

        val normalizedSig = normalizeSignatureLine(highSignalLine)
        val fingerprint = sha256Hex("install|$errorCode|$normalizedSig").take(32)
        val titlePrefix = cause?.title ?: step.label
        val title = if (highSignalLine != redactedMessage && highSignalLine.length > 6) {
            "$titlePrefix：${highSignalLine.take(96)}"
        } else {
            "$titlePrefix：$redactedMessage"
        }.take(MAX_TITLE_CHARS)

        return AutoErrorDraft(
            category = AutoErrorCategory.Install,
            errorCode = errorCode,
            fingerprint = fingerprint,
            title = title,
            message = redactedMessage,
            contextText = contextText
        )
    }

    /** 构造 MaiBot 启动失败草稿：只收录严格匹配的 Python Traceback 或容器/启动级异常行，绝不放行常规业务日志。 */
    fun buildMaiBotBootDraft(
        step: FailureStep,
        cause: FailureCause?,
        message: String,
        recentRunLines: List<String>,
        fallbackLogTail: String?,
        redactor: LogRedactor
    ): AutoErrorDraft {
        val errorCode = cause?.let { "${step.code}/${it.code}" } ?: step.code
        val redactedMessage = redactor.redact(message.trim().ifBlank { step.label }).take(MAX_MESSAGE_CHARS)

        val primaryTb = extractPythonTraceback(recentRunLines)
        val fallbackLines = fallbackLogTail?.lines().orEmpty()
        val fallbackTb = if (primaryTb.isEmpty() && fallbackLines.isNotEmpty()) {
            extractPythonTraceback(fallbackLines)
        } else {
            emptyList()
        }
        val tracebackLines = primaryTb.ifEmpty { fallbackTb }

        val whitelistLines = if (tracebackLines.isNotEmpty()) {
            val statusLines = extractBootWhitelistLines(recentRunLines).takeLast(3)
            (statusLines + tracebackLines).distinct().takeLast(MAX_TRACEBACK_LINES)
        } else {
            val fromRecent = extractBootWhitelistLines(recentRunLines)
            val fromFallback = if (fromRecent.size < 3 && fallbackLines.isNotEmpty()) {
                extractBootWhitelistLines(fallbackLines)
            } else {
                emptyList()
            }
            (fromRecent + fromFallback).distinct().takeLast(MAX_BOOT_WHITELIST_LINES)
        }

        val contextText = if (whitelistLines.isNotEmpty()) {
            trimTailToChars(
                redactor.redact(whitelistLines.joinToString("\n") { it.take(MAX_LINE_CHARS) }),
                MAX_CONTEXT_CHARS
            )
        } else {
            "（未提取到 Python Traceback 或启动异常行；摘要：$redactedMessage）"
        }

        val redactedTbLines = if (tracebackLines.isNotEmpty()) {
            redactor.redact(tracebackLines.joinToString("\n")).lines()
        } else {
            emptyList()
        }
        val pyErrLine = redactedTbLines.lastOrNull { EXCEPTION_CLASS_LINE.matches(it.trim()) }?.trim()
        val lastFileMatch = redactedTbLines.asReversed().firstNotNullOfOrNull { PYTHON_FILE_FRAME.find(it) }

        // 没有 Traceback 时优先挑出真正的宿主/系统报错行，跳过 Process started / exited 等泛化状态行，避免指纹错聚或丢失标题。
        val highSignalLine = if (pyErrLine == null) {
            contextText.lines()
                .map(String::trim)
                .lastOrNull { line ->
                    line.isNotEmpty() &&
                        !line.startsWith("[MaiBot] Process started") &&
                        !line.startsWith("[MaiBot] Process exited with code:") &&
                        !line.startsWith("[MaiDroid] 容器进程收到 SIGKILL(137)") &&
                        !line.startsWith("（未提取到")
                }
        } else {
            null
        }

        val fingerprintSeed = if (pyErrLine != null) {
            val pyFrameKey = lastFileMatch?.let { m ->
                val fileTail = m.groupValues[1].replace('\\', '/').split('/').takeLast(2).joinToString("/")
                "$fileTail:${m.groupValues[2]}"
            }.orEmpty()
            "maibot_boot|$errorCode|${normalizeSignatureLine(pyErrLine)}|$pyFrameKey"
        } else {
            // 计算无 Traceback 指纹时跳过带运行时长与子进程数动态数值的 SIGKILL 补充行，避免同因报错按秒分裂指纹
            val fallbackSig = highSignalLine
                ?: contextText.lines()
                    .lastOrNull { line ->
                        line.isNotBlank() && !line.startsWith("[MaiDroid] 容器进程收到 SIGKILL(137)")
                    }
                ?: contextText.lines().lastOrNull { it.isNotBlank() }
                ?: redactedMessage
            "maibot_boot|$errorCode|no_tb|${normalizeSignatureLine(fallbackSig)}"
        }

        val title = (pyErrLine ?: highSignalLine ?: cause?.title ?: redactedMessage).take(MAX_TITLE_CHARS)
        return AutoErrorDraft(
            category = AutoErrorCategory.MaiBotBoot,
            errorCode = errorCode,
            fingerprint = sha256Hex(fingerprintSeed).take(32),
            title = title,
            message = redactedMessage,
            contextText = contextText
        )
    }

    /** 仅截取最后一段 Python `Traceback (most recent call last):` 到异常结尾行之间的栈帧文本。 */
    private fun extractPythonTraceback(lines: List<String>): List<String> {
        val tbStart = lines.indexOfLast { it.contains("Traceback (most recent call last):") }
        if (tbStart < 0) return emptyList()

        val collected = mutableListOf<String>()
        var seenExceptionLine = false
        for (i in tbStart until minOf(lines.size, tbStart + MAX_TRACEBACK_LINES)) {
            val line = lines[i]
            val trimmed = line.trim()
            if (containsChatPrivacyKeyword(trimmed)) continue

            if (i == tbStart) {
                collected.add(line)
                continue
            }
            if (trimmed.isEmpty()) {
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (next.startsWith("During handling of") || next.startsWith("The above exception") || next.startsWith("Traceback")) {
                    collected.add(line)
                    seenExceptionLine = false
                    continue
                }
                break
            }
            if (trimmed.startsWith("Traceback (most recent call last):") ||
                trimmed.startsWith("During handling of the above exception") ||
                trimmed.startsWith("The above exception was the direct cause")
            ) {
                collected.add(line)
                seenExceptionLine = false
                continue
            }
            if (line.startsWith("  ") && !LOG_TIMESTAMP_LINE.containsMatchIn(trimmed)) {
                collected.add(line)
                continue
            }
            if (EXCEPTION_CLASS_LINE.matches(trimmed)) {
                collected.add(trimmed)
                seenExceptionLine = true
                val nextTrimmed = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (!nextTrimmed.startsWith("During handling") && !nextTrimmed.startsWith("The above exception")) {
                    break
                }
                continue
            }
            if (seenExceptionLine || LOG_TIMESTAMP_LINE.containsMatchIn(trimmed)) {
                break
            }
            // 多行异常消息紧跟在异常头后时最多收录到遇到下一条时间戳为止
            if (collected.size <= 3) break
            collected.add(trimmed)
        }
        return collected
    }

    private fun extractBootWhitelistLines(lines: List<String>): List<String> =
        lines.mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || containsChatPrivacyKeyword(trimmed)) return@mapNotNull null
            if (MAIBOT_BOOT_WHITELIST_REGEXES.any { it.containsMatchIn(trimmed) }) trimmed else null
        }

    private fun containsChatPrivacyKeyword(line: String): Boolean {
        val lower = line.lowercase()
        return CHAT_PRIVACY_KEYWORDS.any { lower.contains(it) }
    }

    private fun isUserGuardrailPrecheckMessage(message: String): Boolean {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return false
        return USER_GUARDRAIL_PRECHECK_MARKERS.any { trimmed.contains(it, ignoreCase = true) }
    }

    // 剔除 NapCat 输出的字符画二维码与扫码提示，防止其占满 50 行尾部上下文并掩盖前置真实异常。
    private fun isQqQrNoiseLine(trimmed: String): Boolean {
        if (QR_ASCII_ART_LINE.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        return NAPCAT_QR_NOISE_KEYWORDS.any { lower.contains(it) }
    }

    // 过滤容器环境下 Electron/LinuxQQ 常见无害输出与纯 SHA-256 哈希行，提升有效报错密度并稳定聚合指纹。
    private fun isBenignRuntimeNoiseLine(trimmed: String): Boolean {
        if (PURE_HEX64_LINE.matches(trimmed)) return true
        val lower = trimmed.lowercase()
        return BENIGN_RUNTIME_NOISE_MARKERS.any { lower.contains(it) }
    }

    // OOBE 完成后在安装/协议端阶段排障时，屏蔽无关的 MaiBot 后台运行异常（如图片嵌入/模型调用错误），
    // 仅在等待 QQ 适配器配置生成时保留插件加载与依赖提示。
    private fun shouldKeepMaiBotLineInInstall(trimmed: String, step: FailureStep, setupComplete: Boolean): Boolean {
        if (!setupComplete || !trimmed.startsWith("[MaiBot]")) return true
        if (step !in ADAPTER_CONFIG_STEPS) return false
        val lower = trimmed.lowercase()
        return ADAPTER_RELEVANT_MAIBOT_KEYWORDS.any { lower.contains(it) }
    }

    private fun normalizeSignatureLine(line: String): String {
        var normalized = line.trim()
        normalized = TIMESTAMP_RE.replace(normalized, "<ts>")
        normalized = DATE_RE.replace(normalized, "<date>")
        normalized = TEMP_PATH_RE.replace(normalized, "/tmp/<tmp>")
        normalized = HEX_RE.replace(normalized, "<hex>")
        normalized = PID_PORT_RE.replace(normalized, "$1=<n>")
        normalized = MULTI_DIGITS_RE.replace(normalized, "<n>")
        normalized = WHITESPACE_RE.replace(normalized, " ")
        return normalized.take(200)
    }

    private fun trimTailToChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val slice = text.takeLast(maxChars)
        val firstNewline = slice.indexOf('\n')
        return if (firstNewline in 0 until slice.lastIndex) slice.substring(firstNewline + 1) else slice
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
