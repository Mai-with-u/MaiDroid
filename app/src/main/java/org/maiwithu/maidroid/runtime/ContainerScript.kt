package org.maiwithu.maidroid.runtime

/**
 * 一段要在 proot 容器里用 /bin/sh 执行的脚本，以及它在容器内的工作目录。
 *
 * 各功能模块（OOBE、MaiBot、NapCat、容器 SSH、在线更新）只负责写出脚本正文，
 * 怎样翻译成宿主机上的 proot 命令行由 ContainerCommandRunner 统一处理。
 */
internal data class ContainerScript(
    val body: String,
    val workingDirectory: String = "/"
)

/** 把任意字符串包成单引号 shell 字面量，内部的单引号按 '"'"' 转义。 */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

/**
 * 按命令行匹配 awk 正则结束容器内进程：先 SIGTERM，等 1 秒后对仍存活的再 SIGKILL。
 * 用于启动前清理上次异常退出留下的 MaiBot / NapCat / sshd，避免端口和锁文件被占。
 * 每结束一个进程打印一行 “<logPrefix><动作> <processName> <pid>”，方便在终端里看清楚杀了什么。
 */
internal fun killMatchingProcessesScript(
    pattern: String,
    processName: String,
    logPrefix: String = ""
): String =
    """
    maidroid_kill_matching() {
        signal="${'$'}1"
        verb="${'$'}2"
        ps -eo pid=,args= | awk '/$pattern/ && !/awk/ { print ${'$'}1 }' | while read pid; do
            [ -n "${'$'}pid" ] || continue
            printf "%s%s %s %s\n" ${shellQuote(logPrefix)} "${'$'}verb" ${shellQuote(processName)} "${'$'}pid"
            kill "${'$'}signal" "${'$'}pid" 2>/dev/null || true
        done
    }
    maidroid_kill_matching -TERM stopping
    sleep 1
    maidroid_kill_matching -KILL "force stopping"
    """.trimIndent()

/** requestExitScript 的结果：都已自己退出、没找到要退出的进程、超时仍未退出（或脚本没能跑起来）。 */
internal enum class RequestedExit {
    Exited,
    NotFound,
    StillRunning
}

private const val REQUESTED_EXIT_NOT_FOUND = 3

/** 按 requestExitScript 约定的退出码读出结果；proot 没能启动时退出码是 -1，按“没确认退出”处理。 */
internal fun requestedExitOf(result: ContainerCommandResult): RequestedExit =
    when (result.exitCode) {
        0 -> RequestedExit.Exited
        REQUESTED_EXIT_NOT_FOUND -> RequestedExit.NotFound
        else -> RequestedExit.StillRunning
    }

/**
 * 停止时要请其自己退出的容器进程：命令行（ps 的 args）匹配 awk 正则 pattern、且不匹配 exclude 的那些。
 * processName 只用于日志。正则里的点写成 [.]，免得匹配到脚本自己的命令行里那份正则原文。
 */
internal data class ExitTarget(
    val pattern: String,
    val exclude: String,
    val processName: String
)

/**
 * 请容器里的 target 进程自己退出：发 SIGTERM -> 每秒查一次 -> 都退出了就返回 0，
 * 一个也没找到返回 3，等满 timeoutSeconds 秒还有没退出的返回 4（结果用 requestedExitOf 读）。
 *
 * 停止、重启和安全退出都要让 MaiBot、QQ 走完各自的关闭流程（保存数据），只能这样在容器里直接给它们发信号：
 * 宿主侧的 Process.destroy() 只能给 proot 发 SIGTERM，而 proot 会忽略它，信号到不了容器里的进程；
 * proot 被 SIGKILL 后，容器里的进程也不会跟着结束，而是脱离 proot 接着跑（2026-11 真机实测）。
 * awk 条件里排除 awk 自身，免得匹配到本脚本的命令行。
 */
internal fun requestExitScript(
    target: ExitTarget,
    timeoutSeconds: Int,
    logPrefix: String = ""
): String =
    """
    maidroid_exit_targets() {
        ps -eo pid=,args= | awk '/${target.pattern}/ && !/${target.exclude}/ && !/awk/ { print ${'$'}1 }'
    }
    targets="${'$'}(maidroid_exit_targets)"
    if [ -z "${'$'}targets" ]; then
        printf "%sno running %s found\n" ${shellQuote(logPrefix)} ${shellQuote(target.processName)}
        exit $REQUESTED_EXIT_NOT_FOUND
    fi
    for pid in ${'$'}targets; do
        printf "%sasking %s %s to exit\n" ${shellQuote(logPrefix)} ${shellQuote(target.processName)} "${'$'}pid"
        kill -TERM "${'$'}pid" 2>/dev/null || true
    done
    waited=0
    while [ -n "${'$'}(maidroid_exit_targets)" ]; do
        if [ "${'$'}waited" -ge $timeoutSeconds ]; then
            printf "%s%s still running after %s seconds\n" ${shellQuote(logPrefix)} ${shellQuote(target.processName)} "${'$'}waited"
            exit 4
        fi
        sleep 1
        waited=${'$'}((waited + 1))
    done
    printf "%s%s exited after %s seconds\n" ${shellQuote(logPrefix)} ${shellQuote(target.processName)} "${'$'}waited"
    exit 0
    """.trimIndent()
