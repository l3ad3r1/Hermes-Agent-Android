package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device LLM provider backed by llama.cpp via JNI ([LlamaInferenceEngine]).
 *
 * Requires:
 *  1. The native library (libhermes-llama.so) to be present in the APK.
 *     Build it by setting `hermes.ondevice.enabled=true` and
 *     `hermes.ondevice.llamaCppPath=…` in hermes.local.properties, then
 *     installing the Android NDK (r27+).
 *  2. A GGUF model file on the device. Set its path in Settings → On-Device LLM.
 *
 * When the native library is absent, [isAvailable] returns false and the
 * [HybridLlmRouter] falls through to the cloud provider.
 */
@Singleton
class OnDeviceLlmProvider @Inject constructor(
    private val engine: LlamaInferenceEngine,
    private val settings: SettingsRepository,
) : LlmProvider {

    override val name: String = "On-Device (llama.cpp)"
    override val isOnDevice: Boolean = true
    override val model: String
        get() = runCatching { engine.javaClass.simpleName }.getOrElse { "llama.cpp" }

    // ── LlmProvider ──────────────────────────────────────────────────────────

    override suspend fun isAvailable(): Boolean {
        if (!LlamaInferenceEngine.isNativeAvailable) return false
        val s = settings.current()
        return s.onDeviceEnabled && s.onDeviceModelPath.isNotBlank()
    }

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
        val sb = StringBuilder()
        stream(messages).collect { chunk ->
            if (chunk is LlmStreamChunk.Delta) sb.append(chunk.text)
        }
        return LlmResponse(
            content = sb.toString().trim(),
            tokensUsed = 0,
            model = name,
            finishReason = "stop",
        )
    }

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        val s = settings.current()
        if (!s.onDeviceEnabled || s.onDeviceModelPath.isBlank()) {
            emit(LlmStreamChunk.Error("On-device LLM is not configured. Set a model path in Settings."))
            return@flow
        }
        if (!LlamaInferenceEngine.isNativeAvailable) {
            emit(LlmStreamChunk.Error("Native library (libhermes-llama.so) not bundled in this build."))
            return@flow
        }

        runCatching {
            engine.initialize()
            engine.ensureModel(s.onDeviceModelPath)

            val (systemPrompt, conversationText) = formatMessages(messages)

            engine.generateStream(
                systemPrompt = systemPrompt ?: "",
                conversationText = conversationText,
                maxTokens = 1024,
            ).collect { token -> emit(LlmStreamChunk.Delta(token)) }

            emit(LlmStreamChunk.Done)
        }.onFailure { e ->
            Timber.e(e, "On-device inference error")
            emit(LlmStreamChunk.Error(e.message ?: "Inference failed"))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Split messages into (systemPrompt, conversationText).
     *
     * Models with an explicit chat template (most GGUF instruct models) apply
     * their own formatting in ai_chat.cpp. For models without a template the
     * "Human: / Assistant:" format is used as a widely-understood fallback.
     */
    private fun formatMessages(messages: List<LlmMessage>): Pair<String?, String> {
        val systemMsg = messages.firstOrNull { it.role == "system" }?.content
        val conversationText = messages
            .filter { it.role != "system" }
            .joinToString("\n") { msg ->
                when (msg.role) {
                    "user"      -> "Human: ${msg.content}"
                    "assistant" -> "Assistant: ${msg.content}"
                    "tool"      -> "Tool result: ${msg.content}"
                    else        -> "${msg.role}: ${msg.content}"
                }
            }
        return Pair(systemMsg, conversationText)
    }
}
