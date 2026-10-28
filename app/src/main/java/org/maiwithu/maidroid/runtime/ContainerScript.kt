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
