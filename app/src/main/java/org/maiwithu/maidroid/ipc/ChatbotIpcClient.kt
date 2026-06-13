package org.maiwithu.maidroid.ipc

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Client for communicating with the Python chatbot backend via
 * Unix Domain Socket (abstract namespace).
 *
 * The Python server listens on an abstract socket with name `\0maidroid_ipc_socket`
 * and handles JSON commands like `chat`, `ping`, `config`, etc.
 *
 * Protocol:
 * - One JSON object per line (newline-delimited JSON)
 * - Request: {"cmd": "...", "args": {...}}
 * - Response: {"status": "ok"|"error", ...}
 */
class ChatbotIpcClient {

    companion object {
        private const val TAG = "ChatbotIpcClient"
        private const val SOCKET_NAME = "\u0000maidroid_ipc_socket"

        // Timeouts
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Send a command to the Python backend and return the parsed response.
     *
     * @param cmd The command name (e.g. "chat", "ping", "config")
     * @param args Optional arguments to include in the request
     * @param timeoutMs Timeout for the entire request/response cycle
     * @return The parsed JSON response object, or null on failure
     */
    suspend fun sendCommand(
        cmd: String,
        args: Map<String, String> = emptyMap(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS
    ): Result<JsonObject> = withContext(Dispatchers.IO) {
        try {
            withTimeout(timeoutMs) {
                val socket = connectSocket()
                try {
                    // Build and send request
                    val request = buildJsonObject {
                        put("cmd", cmd)
                        if (args.isNotEmpty()) {
                            put("args", buildJsonObject {
                                args.forEach { (k, v) -> put(k, v) }
                            })
                        }
                    }

                    val writer = BufferedWriter(OutputStreamWriter(socket.outputStream))
                    writer.write(request.toString() + "\n")
                    writer.flush()

                    // Read response (one line)
                    val reader = BufferedReader(InputStreamReader(socket.inputStream))
                    val responseLine = reader.readLine()
                        ?: return@withTimeout Result.failure(
                            IllegalStateException("Empty response from server")
                        )

                    val response = json.parseToJsonElement(responseLine).jsonObject
                    Result.success(response)
                } finally {
                    try { socket.close() } catch (_: Exception) { }
                }
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Request timed out: cmd=$cmd")
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "IPC error for cmd=$cmd: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Connect to the Unix Domain Socket (abstract namespace).
     */
    private fun connectSocket(): LocalSocket {
        val socket = LocalSocket()
        val address = LocalSocketAddress(
            SOCKET_NAME,
            LocalSocketAddress.Namespace.ABSTRACT
        )
        socket.connect(address)
        socket.soTimeout = REQUEST_TIMEOUT_MS.toInt()
        return socket
    }

    // ── Convenience methods ──────────────────────────────────────────

    /**
     * Send a chat message and get the response text.
     */
    suspend fun chat(
        text: String,
        conversationId: String? = null
    ): Result<ChatResponse> = withContext(Dispatchers.IO) {
        val args = mutableMapOf("text" to text)
        conversationId?.let { args["conversation_id"] = it }

        sendCommand("chat", args).map { response ->
            ChatResponse(
                text = response["response"]?.toString()?.removeSurrounding("\"") ?: "",
                status = response["status"]?.toString()?.removeSurrounding("\"") ?: "ok"
            )
        }
    }

    /**
     * Ping the Python backend to check if it's alive.
     */
    suspend fun ping(): Boolean {
        return sendCommand("ping", timeoutMs = 5_000L)
            .map { it["status"]?.toString()?.removeSurrounding("\"") == "ok" }
            .getOrDefault(false)
    }

    /**
     * Request the backend's current configuration.
     */
    suspend fun getConfig(): Result<JsonObject> {
        return sendCommand("config")
    }

    /**
     * Update the backend's configuration.
     */
    suspend fun updateConfig(config: Map<String, String>): Result<JsonObject> {
        return sendCommand("config_update", config)
    }

    /**
     * Clear the conversation history.
     */
    suspend fun clearHistory(): Result<JsonObject> {
        return sendCommand("clear_history")
    }
}

/**
 * Simple data class representing a chat response from the Python backend.
 */
data class ChatResponse(
    val text: String,
    val status: String
)
