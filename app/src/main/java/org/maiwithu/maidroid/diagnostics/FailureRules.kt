package org.maiwithu.maidroid.diagnostics

// 从失败命令的输出里认原因的规则表：每条原因对应几段 apt、git、curl、uv、Python 和 proot 实际会打印的报错文字。
// 规则按表里的顺序逐条试，排在前面的先认：具体的原因（空间满、时间不对）放在笼统的网络错误前面，
// 网络错误里又按“解析不了 -> 超时 -> 断开 -> 服务器报错”排，几种同时出现时（各镜像失败的方式不同）报最根本的那个。
// 只看输出的最后 TAIL_LINES 行：前面的报错可能已经被重试或换镜像自愈了，最后几百行才是让这一步失败的那次。
// 应用自己下载（自更新）走 OkHttp，失败时记的是“异常类名: 说明”，所以网络几类里也列了对应的 Java 异常名。
// 要补新原因时，先从真机日志或 Issue 里拿到原文，再加到这里并补测试。

private const val TAIL_LINES = 400

private class FailureRule(val cause: FailureCause, val patterns: List<Regex>)

private fun rule(cause: FailureCause, vararg literals: String, extra: List<Regex> = emptyList()) =
    FailureRule(cause, literals.map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) } + extra)

private val FAILURE_RULES = listOf(
    rule(FailureCause.DiskFull, "No space left on device", "ENOSPC", "Disk quota exceeded", "存储空间不足"),
    rule(
        FailureCause.OutOfMemory, "MemoryError", "Cannot allocate memory", "Out of memory",
        // 被 OOM killer 结束时，外层 shell 只打印一行 Killed
        extra = listOf(Regex("""^\s*Killed\s*$""", RegexOption.MULTILINE))
    ),
    rule(FailureCause.UnsupportedCpu, "Exec format error", "cannot execute binary file"),
    // MaiBot 启动时发现数据库比代码新会拒绝启动（上游 src/common/database/migrations/bootstrap.py 的报错原文）
    rule(FailureCause.DatabaseDowngrade, "当前数据库版本高于代码内注册的最新迁移版本"),
    // apt 的 Release 文件“还没生效 / 已经过期”，以及 TLS 证书“还没生效”，都是手机时间偏差太大
    rule(
        FailureCause.ClockSkew, "is not valid yet", "invalid since", "certificate is not yet valid", "NotValidYet",
        "CertificateNotYetValidException"
    ),
    rule(
        FailureCause.Tls, "SSL certificate problem", "certificate verify failed", "server certificate verification failed",
        "unable to get local issuer certificate", "invalid peer certificate", "certificate has expired",
        "CERTIFICATE_VERIFY_FAILED", "UnknownIssuer", "SSLHandshakeException", "CertPathValidatorException"
    ),
    rule(
        FailureCause.Dns, "Could not resolve host", "Temporary failure in name resolution", "Name or service not known",
        "Temporary failure resolving", "Could not resolve", "dns error", "failed to lookup address information",
        "No address associated with hostname", "Unable to resolve host", "UnknownHostException"
    ),
    rule(
        FailureCause.Timeout, "timed out", "Timeout was reached", "Operation too slow", "deadline has elapsed",
        "Request timeout", "SocketTimeoutException"
    ),
    rule(
        FailureCause.ConnectionLost, "Connection refused", "Connection reset", "Network is unreachable",
        "No route to host", "Failed to connect to", "connection closed", "early EOF", "unexpected EOF",
        "RPC failed", "GnuTLS recv error", "SSL_ERROR_SYSCALL", "error sending request", "ConnectException"
    ),
    rule(
        FailureCause.HttpError, "The requested URL returned error", "403 Forbidden", "404 Not Found",
        "502 Bad Gateway", "503 Service Unavailable", "504 Gateway", "HTTP status client error",
        "HTTP status server error"
    ),
    rule(FailureCause.AptMirrorSyncing, "Hash Sum mismatch", "File has unexpected size", "Mirror sync in progress"),
    rule(
        FailureCause.AptLocked, "Could not get lock", "Unable to acquire the dpkg frontend lock",
        "Unable to lock the administration directory"
    ),
    rule(FailureCause.GitLocked, ".lock': File exists", "Another git process seems to be running"),
    rule(FailureCause.GitBroken, "bad object", "loose object", "is corrupt", "unable to read tree", "packfile"),
    rule(FailureCause.DependencyConflict, "No solution found when resolving", "unsatisfiable"),
    rule(
        FailureCause.DependencyBuild, "Failed to build", "Build backend failed", "can't find Rust compiler",
        "command 'gcc' failed", "No such file or directory: 'cc'"
    ),
    rule(FailureCause.MissingModule, "ModuleNotFoundError", "No module named", "ImportError"),
    rule(FailureCause.ConfigSyntax, "TOMLDecodeError", "tomlkit.exceptions", "Invalid TOML"),
    rule(FailureCause.PortInUse, "Address already in use", "[Errno 98]", "EADDRINUSE"),
    rule(FailureCause.DatabaseLocked, "database is locked")
)

/**
 * MaiBot 跑起来之后反复退出时只认这些原因。它运行中的输出里常有不致命的网络报错（大模型请求超时、插件下载失败），
 * 按网络原因报会把人带偏；能让它起不来的是下面这几类。
 */
internal val MAIBOT_RUNTIME_CAUSES: Set<FailureCause> = setOf(
    FailureCause.DiskFull,
    FailureCause.OutOfMemory,
    FailureCause.UnsupportedCpu,
    FailureCause.DatabaseDowngrade,
    FailureCause.MissingModule,
    FailureCause.ConfigSyntax,
    FailureCause.PortInUse,
    FailureCause.DatabaseLocked
)

/**
 * 从一步失败的输出里认原因：按规则表顺序，第一条在最后 TAIL_LINES 行里出现过的就是；都没有返回 null。
 * among 不为空时只在这些原因里认，见 MAIBOT_RUNTIME_CAUSES。
 */
internal fun diagnoseFailureCause(output: String, among: Set<FailureCause>? = null): FailureCause? {
    if (output.isBlank()) return null
    val tail = output.lineSequence().toList().takeLast(TAIL_LINES).joinToString("\n")
    return FAILURE_RULES
        .filter { among == null || it.cause in among }
        .firstOrNull { rule -> rule.patterns.any { it.containsMatchIn(tail) } }
        ?.cause
}
