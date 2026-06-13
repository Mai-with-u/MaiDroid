package org.maiwithu.maidroid.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maiwithu.maidroid.ipc.ChatResponse
import org.maiwithu.maidroid.ipc.ChatbotIpcClient

/**
 * Repository that mediates between the UI (ViewModel) and the Python
 * chatbot backend via IPC.
 *
 * Maintains an in-memory list of chat messages for the current session.
 */
class ChatbotRepository(
    private val ipcClient: ChatbotIpcClient = ChatbotIpcClient()
) {

    data class ChatMessage(
        val id: Long,
        val role: Role,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class Role { USER, ASSISTANT, SYSTEM }

    private var messageIdCounter = 0L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Send a user message and receive the assistant's response.
     */
    suspend fun sendMessage(text: String): Result<ChatResponse> {
        // Add user message
        val userMsg = ChatMessage(
            id = messageIdCounter++,
            role = Role.USER,
            content = text
        )
        addMessage(userMsg)
        _isLoading.value = true

        return try {
            val result = ipcClient.chat(text)

            result.onSuccess { response ->
                val assistantMsg = ChatMessage(
                    id = messageIdCounter++,
                    role = Role.ASSISTANT,
                    content = response.text
                )
                addMessage(assistantMsg)
            }

            result
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Check if the Python backend is alive.
     */
    suspend fun checkBackendAlive(): Boolean {
        return ipcClient.ping()
    }

    /**
     * Clear the local message history and the backend's conversation.
     */
    suspend fun clearHistory() {
        _messages.value = emptyList()
        messageIdCounter = 0
        ipcClient.clearHistory()
    }

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }
}
