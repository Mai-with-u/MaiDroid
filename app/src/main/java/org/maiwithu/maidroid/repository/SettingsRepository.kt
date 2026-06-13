package org.maiwithu.maidroid.repository

import com.tencent.mmkv.MMKV

/**
 * Repository for managing app configuration via MMKV.
 *
 * Persists settings like API keys, model selection, and system prompt.
 * These are synced to the Python backend's config file when the service starts.
 *
 * MMKV provides efficient, instant-commit key-value storage backed by mmap,
 * suitable for high-frequency writes like chat history caching.
 */
class SettingsRepository(
    private val kv: MMKV = MMKV.defaultMMKV()
) {

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_SERVICE_AUTO_START = "service_auto_start"
        private const val KEY_DEBIAN_PATH = "debian_path"
        private const val KEY_CHATBOT_PATH = "chatbot_path"

        private const val DEFAULT_MODEL = "default"
        private const val DEFAULT_SYSTEM_PROMPT = "你是一个有帮助的AI助手。"
    }

    // ── API Key ──────────────────────────────────────────────────────

    fun getApiKey(): String = kv.decodeString(KEY_API_KEY, "") ?: ""
    fun setApiKey(key: String) = kv.encode(KEY_API_KEY, key)

    // ── Model ────────────────────────────────────────────────────────

    fun getModel(): String = kv.decodeString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    fun setModel(model: String) = kv.encode(KEY_MODEL, model)

    // ── System Prompt ────────────────────────────────────────────────

    fun getSystemPrompt(): String = kv.decodeString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    fun setSystemPrompt(prompt: String) = kv.encode(KEY_SYSTEM_PROMPT, prompt)

    // ── Setup State ──────────────────────────────────────────────────

    fun isSetupComplete(): Boolean = kv.decodeBool(KEY_SETUP_COMPLETE, false)
    fun setSetupComplete(complete: Boolean) = kv.encode(KEY_SETUP_COMPLETE, complete)

    // ── Service Auto-Start ───────────────────────────────────────────

    fun isServiceAutoStart(): Boolean = kv.decodeBool(KEY_SERVICE_AUTO_START, true)
    fun setServiceAutoStart(autoStart: Boolean) = kv.encode(KEY_SERVICE_AUTO_START, autoStart)

    // ── Paths ────────────────────────────────────────────────────────

    fun getDebianPath(): String = kv.decodeString(KEY_DEBIAN_PATH, "") ?: ""
    fun setDebianPath(path: String) = kv.encode(KEY_DEBIAN_PATH, path)

    fun getChatbotPath(): String = kv.decodeString(KEY_CHATBOT_PATH, "") ?: ""
    fun setChatbotPath(path: String) = kv.encode(KEY_CHATBOT_PATH, path)

    // ── Build config map for Python backend ──────────────────────────

    fun buildConfigMap(): Map<String, String> = mapOf(
        "api_key" to getApiKey(),
        "model" to getModel(),
        "system_prompt" to getSystemPrompt()
    )
}
