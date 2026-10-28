package org.maiwithu.maidroid.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** proot 容器内一次性命令的执行结果。 */
internal data class ContainerCommandResult(
    val exitCode: Int,
    val output: String
) {
    val success: Boolean get() = exitCode == 0

    /** 失败提示取最后一行非空输出：容器脚本通常在退出前才打印真正的失败原因。 */
    fun summary(): String =
        output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: "命令退出码: $exitCode"
}

/**
 * 在 proot 容器里启动命令的唯一入口。
 *
 * 所有容器命令都以 rootfs 为工作目录、合并 stderr 并注入 proot 环境变量；
 * 会拉起 MaiBot 的命令（OOBE、NapCat 安装、MaiBot 本体）额外注入协议 hash，
 * 避免 MaiBot 启动时卡在 EULA 确认。
 */
internal class ContainerCommandRunner(private val config: RuntimePaths) {

    /** 脚本在宿主机上实际执行的 proot 命令行，供安装页和终端日志展示。 */
    fun commandLine(script: ContainerScript): List<String> =
        config.shellCommand(script.body, script.workingDirectory)

    /** 启动长驻的容器脚本（MaiBot、sshd、NapCat），输出读取和生命周期由调用方负责。 */
    fun start(script: ContainerScript, extraEnvironment: Map<String, String> = emptyMap()): Process {
        val processBuilder = ProcessBuilder(commandLine(script))
            .directory(config.rootfsDir)
            .redirectErrorStream(true)
        processBuilder.environment().putAll(config.prootEnvironment())
        processBuilder.environment().putAll(extraEnvironment)
        return processBuilder.start()
    }

    /** 执行一次性的容器脚本，行为与按命令行执行的 run 相同。 */
    suspend fun run(
        script: ContainerScript,
        extraEnvironment: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {}
    ): ContainerCommandResult = runProcess(onLine) { start(script, extraEnvironment) }

    /** 启动长驻进程（MaiBot），输出读取和生命周期由调用方负责。 */
    fun start(command: List<String>, includeAgreement: Boolean = false): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(config.rootfsDir)
            .redirectErrorStream(true)
        processBuilder.environment().putAll(config.prootEnvironment())
        if (includeAgreement) {
            processBuilder.environment().putAll(config.agreementEnvironment())
        }
        return processBuilder.start()
    }

    /**
     * 执行一次性命令：逐行回调输出 -> 等待退出 -> 返回完整输出。
     * 进程起不来时不抛异常，而是把原因当作一行输出回调出去并以退出码 -1 返回，
     * 这样调用方只需要处理一种失败形态。
     */
    suspend fun run(
        command: List<String>,
        includeAgreement: Boolean = false,
        onLine: (String) -> Unit = {}
    ): ContainerCommandResult = runProcess(onLine) { start(command, includeAgreement) }

    private suspend fun runProcess(
        onLine: (String) -> Unit,
        launch: () -> Process
    ): ContainerCommandResult = withContext(Dispatchers.IO) {
        val process = try {
            launch()
        } catch (e: Exception) {
            val message = e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            onLine(message)
            return@withContext ContainerCommandResult(exitCode = -1, output = message)
        }

        try {
            val output = StringBuilder()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trimEnd('\r')
                    output.appendLine(line)
                    onLine(line)
                }
            }
            ContainerCommandResult(exitCode = process.waitFor(), output = output.toString())
        } catch (e: Exception) {
            val message = e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            onLine(message)
            ContainerCommandResult(exitCode = -1, output = message)
        } finally {
            // 读取中途出错时进程可能仍在跑，直接结束它，避免残留的 proot 进程占住容器。
            if (process.isAlive) {
                process.destroy()
            }
        }
    }
}
