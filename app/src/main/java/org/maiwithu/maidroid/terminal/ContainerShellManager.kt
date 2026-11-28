package org.maiwithu.maidroid.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import java.lang.ref.WeakReference

/** 仅当 Termux 会话已经实际派生子进程（pid > 0）时才允许调用 finishIfRunning()，避免 pid == 0 时触发 Os.kill(0, SIGKILL) 误杀整个应用进程组。 */
internal fun canTerminateTerminalSessionPid(pid: Int): Boolean = pid > 0

/** 本地容器交互终端的运行阶段。 */
internal sealed interface ContainerShellPhase {
    data object Idle : ContainerShellPhase
    data class NotReady(val reason: String) : ContainerShellPhase
    data object Running : ContainerShellPhase
    data class Exited(val exitCode: Int) : ContainerShellPhase
}

/** 暴露给终端页面的不可变会话状态。 */
internal data class ContainerShellState(
    val phase: ContainerShellPhase = ContainerShellPhase.Idle,
    val session: TerminalSession? = null,
    val sessionGeneration: Int = 0
)

/**
 * 进程级容器本地交互终端会话管理器（无需 SSH，直接经伪终端接入 proot 容器 bash）：
 * - 用户首次滑入/切入「终端」标签页时懒启动，关闭终端输出页或旋转屏幕不销毁当前 shell 现场；
 * - 持有当前挂载的 TerminalView 弱引用，并在 TerminalSessionClient 回调中驱动屏幕刷新、剪贴板读写与退出状态切换；
 * - 安全退出时永久拦截后续启动并结束当前会话。
 */
internal class ContainerShellManager(
    context: Context,
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner
) {
    private companion object {
        const val TAG = "ContainerShell"
        const val TRANSCRIPT_ROWS = 2000
    }

    private val appContext = context.applicationContext
    private val scope = backgroundScope("本地交互终端", Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private var attachedViewRef: WeakReference<TerminalView>? = null
    private var shuttingDown = false

    private val _state = MutableStateFlow(ContainerShellState())
    val state: StateFlow<ContainerShellState> = _state.asStateFlow()

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession === _state.value.session) {
                attachedViewRef?.get()?.onScreenUpdated()
            }
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit

        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession !== _state.value.session) return
            val exitCode = finishedSession.exitStatus
            _state.update { it.copy(phase = ContainerShellPhase.Exited(exitCode)) }
            attachedViewRef?.get()?.onScreenUpdated()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            appContext.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("MaiDroid 终端", text))
            Toast.makeText(appContext, "已复制选中文本", Toast.LENGTH_SHORT).show()
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val target = session ?: return
            val pasteText = readClipboardText() ?: return
            target.emulator?.paste(pasteText)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            if (session === _state.value.session) {
                attachedViewRef?.get()?.onScreenUpdated()
            }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            attachedViewRef?.get()?.setTerminalCursorBlinkerState(state, true)
        }

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "[${tag.orEmpty()}] ${message.orEmpty()}")
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(TAG, "[${tag.orEmpty()}] ${message.orEmpty()}")
        }

        override fun logInfo(tag: String?, message: String?) {
            Log.i(TAG, "[${tag.orEmpty()}] ${message.orEmpty()}")
        }

        override fun logDebug(tag: String?, message: String?) {
            Log.d(TAG, "[${tag.orEmpty()}] ${message.orEmpty()}")
        }

        override fun logVerbose(tag: String?, message: String?) {
            Log.v(TAG, "[${tag.orEmpty()}] ${message.orEmpty()}")
        }

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(TAG, "[${tag.orEmpty()}] ${message.orEmpty()}", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(TAG, "[${tag.orEmpty()}]", e)
        }
    }

    /** 确保本地终端会话已就绪并运行；已存在存活会话时直接复用。 */
    fun ensureStarted() {
        if (shuttingDown) return
        scope.launch {
            sessionMutex.withLock {
                if (shuttingDown) return@withLock
                val current = _state.value.session
                if (current != null && current.isRunning) {
                    return@withLock
                }
                startNewSessionLocked()
            }
        }
    }

    /** 结束当前会话并重新拉起一个新的交互式容器 Shell。 */
    fun restart() {
        if (shuttingDown) return
        scope.launch {
            sessionMutex.withLock {
                if (shuttingDown) return@withLock
                finishCurrentSessionLocked()
                startNewSessionLocked()
            }
        }
    }

    fun attachView(view: TerminalView) {
        attachedViewRef = WeakReference(view)
    }

    fun detachView(view: TerminalView) {
        if (attachedViewRef?.get() === view) {
            attachedViewRef = null
        }
    }

    /** 向当前会话发送 Ctrl+L 清空可见屏幕，同时保留 bash 上下文与工作目录。 */
    fun clearScreen() {
        sendText("\u000c")
    }

    /** 将系统剪贴板文本粘贴至当前交互终端（带 bracketed paste 适配）。 */
    fun pasteFromClipboard(): Boolean {
        val session = _state.value.session ?: return false
        if (!session.isRunning) return false
        val text = readClipboardText() ?: return false
        val emulator = session.emulator
        if (emulator != null) {
            emulator.paste(text)
        } else {
            sendText(text)
        }
        return true
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        sendBytes(text.toByteArray(Charsets.UTF_8))
    }

    fun sendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val session = _state.value.session ?: return
        if (!session.isRunning) return
        session.write(bytes, 0, bytes.size)
    }

    /** 应用安全退出时调用：禁止后续再拉起会话，并结束当前交互式 PTY 进程。 */
    fun stopForExit() {
        shuttingDown = true
        _state.value.session?.let { session ->
            // 尚未完成 initializeEmulator 的会话 pid 为 0，直接调 finishIfRunning 会向自身进程组发 SIGKILL
            if (canTerminateTerminalSessionPid(session.pid)) {
                runCatching { session.finishIfRunning() }
            }
        }
        attachedViewRef = null
        _state.update { ContainerShellState(phase = ContainerShellPhase.Idle, session = null, sessionGeneration = it.sessionGeneration + 1) }
    }

    private suspend fun startNewSessionLocked() {
        val problem = withContext(Dispatchers.IO) { readiness.containerProblem() }
        if (shuttingDown) return
        if (problem != null) {
            _state.update { it.copy(phase = ContainerShellPhase.NotReady(problem), session = null) }
            return
        }

        val (commandLine, envArray) = withContext(Dispatchers.IO) {
            val workDir = if (paths.maiBotDir.isDirectory) InContainerPaths.MAIBOT_DIR else "/root"
            val cmd = commandRunner.interactiveShellCommandLine(workDir)
            val env = commandRunner.interactiveShellEnvironment()
            cmd to env
        }
        if (shuttingDown) return

        // Termux TerminalSession.initializeEmulator() 在 TerminalView 初次完成尺寸测量后自动触发 createSubprocess
        val session = TerminalSession(
            commandLine.first(),
            paths.rootfsDir.absolutePath,
            commandLine.toTypedArray(),
            envArray,
            TRANSCRIPT_ROWS,
            sessionClient
        )
        _state.update { current ->
            ContainerShellState(
                phase = ContainerShellPhase.Running,
                session = session,
                sessionGeneration = current.sessionGeneration + 1
            )
        }
    }

    private fun finishCurrentSessionLocked() {
        _state.value.session?.let { oldSession ->
            if (canTerminateTerminalSessionPid(oldSession.pid)) {
                runCatching { oldSession.finishIfRunning() }
            }
        }
    }

    private fun readClipboardText(): String? {
        val clip = appContext.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount <= 0) return null
        return clip.getItemAt(0).coerceToText(appContext)?.toString()?.takeIf { it.isNotEmpty() }
    }
}
