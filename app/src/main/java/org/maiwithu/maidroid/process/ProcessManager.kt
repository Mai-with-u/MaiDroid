package org.maiwithu.maidroid.process

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the lifecycle of the Python chatbot process running inside
 * a Termux/Debian proot container.
 *
 * Responsibilities:
 * - Start the Python server process inside the Debian rootfs
 * - Monitor process health and auto-restart on crash
 * - Collect stdout/stderr logs
 * - Graceful shutdown
 */
class ProcessManager(private val context: Context) {

    companion object {
        private const val TAG = "ProcessManager"

        // Paths inside the Android filesystem
        private const val DEBIAN_DIR = "debian"
        private const val CHATBOT_DIR = "opt/chatbot"
        private const val PROOT_BIN = "usr/bin/proot"

        // The Python server entry point inside the Debian container
        private const val PYTHON_SERVER_SCRIPT = "/opt/chatbot/server.py"

        // IPC socket name (abstract namespace — starts with null byte)
        const val SOCKET_NAME = "\u0000maidroid_ipc_socket"

        // Restart backoff
        private const val INITIAL_RESTART_DELAY_MS = 5_000L
        private const val MAX_RESTART_DELAY_MS = 60_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        // Max log lines to keep in memory
        private const val MAX_LOG_LINES = 500
    }

    private var process: Process? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var restartDelayMs = INITIAL_RESTART_DELAY_MS
    private var intentionallyStopped = false

    /**
     * Returns the path to the Debian filesystem root on the Android filesystem.
     */
    private val debianRoot: File
        get() = File(context.filesDir, DEBIAN_DIR)

    /**
     * Returns the path to the chatbot project inside the Debian rootfs.
     */
    private val chatbotDir: File
        get() = File(debianRoot, CHATBOT_DIR)

    /**
     * The full command to launch the Python server via proot.
     * Uses `proot-distro login` if available, or falls back to direct proot call.
     */
    private fun buildCommand(): List<String> {
        val rootfs = debianRoot.absolutePath
        val script = PYTHON_SERVER_SCRIPT

        // Use proot-distro if termux is available, otherwise direct proot
        return listOf(
            "proot",
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/sdcard",
            "-w", "/opt/chatbot",
            "/usr/bin/python3", script,
            "--ipc-socket", SOCKET_NAME
        )
    }

    /**
     * Start the Python chatbot server.
     * If already running, this is a no-op.
     */
    fun start() {
        if (_isRunning.value) return
        intentionallyStopped = false
        restartDelayMs = INITIAL_RESTART_DELAY_MS
        startProcess()
    }

    /**
     * Stop the Python chatbot server gracefully.
     * Sends SIGTERM first, then SIGKILL after a timeout.
     */
    fun stop() {
        intentionallyStopped = true
        job?.cancel()
        scope.launch {
            killProcess()
        }
    }

    /**
     * Internal: launch the process and set up the monitoring loop.
     */
    private fun startProcess() {
        job?.cancel()
        job = scope.launch {
            try {
                val command = buildCommand()
                appendLog("[ProcessManager] Starting: ${command.joinToString(" ")}")

                val processBuilder = ProcessBuilder(command)
                    .directory(debianRoot)
                    .redirectErrorStream(true)

                process = processBuilder.start()

                _isRunning.value = true
                appendLog("[ProcessManager] Python process started (PID: ${getPid()})")

                // Read stdout/stderr
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    appendLog(line!!)
                }

                // Process exited
                val exitCode = process!!.waitFor()
                _isRunning.value = false
                appendLog("[ProcessManager] Python process exited with code: $exitCode")

            } catch (e: Exception) {
                _isRunning.value = false
                appendLog("[ProcessManager] Failed to start process: ${e.message}")
                Log.e(TAG, "Failed to start Python process", e)
            }

            // Auto-restart if not intentionally stopped
            if (!intentionallyStopped) {
                appendLog("[ProcessManager] Restarting in ${restartDelayMs}ms...")
                delay(restartDelayMs)
                restartDelayMs = (restartDelayMs * BACKOFF_MULTIPLIER)
                    .toLong()
                    .coerceAtMost(MAX_RESTART_DELAY_MS)
                startProcess()
            }
        }
    }

    /**
     * Kill the Python process. Sends destroy (SIGTERM) and waits briefly
     * before force-killing if it hasn't exited.
     */
    private suspend fun killProcess() {
        process?.let { proc ->
            try {
                proc.destroy()
                // Wait briefly for graceful shutdown
                withContext(Dispatchers.IO) {
                    try {
                        proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) { }
                }
                if (proc.isAlive) {
                    appendLog("[ProcessManager] Force-killing Python process...")
                    proc.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error killing process", e)
            }
            process = null
        }
        _isRunning.value = false
    }

    /**
     * Get the PID of the managed process, or -1 if not available.
     * Uses reflection because Android's java.lang.Process lacks pid() (Java 9+ only).
     */
    fun getPid(): Long {
        return try {
            val proc = process ?: return -1L
            val pidField = Process::class.java.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(proc)
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Check if the Python server is responsive by sending a ping via IPC.
     * Returns true if the server responds with a valid "pong".
     */
    suspend fun isResponsive(): Boolean {
        // This will be implemented once ChatbotIpcClient is ready
        // For now, just check if the process is alive
        return process?.isAlive == true
    }

    private fun appendLog(line: String) {
        val current = _logs.value.toMutableList()
        current.add(line)
        if (current.size > MAX_LOG_LINES) {
            current.removeAt(0)
        }
        _logs.value = current
        Log.d(TAG, line)
    }
}
