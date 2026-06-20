package com.hermes.agent.data.llm

import com.hermes.agent.data.performance.MemoryPressureMonitor
import com.hermes.agent.data.performance.MemoryPressureState
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * On-device LLM provider — Phase 4 mock with tool-call support, idle
 * unload, and tiered memory-pressure shedding.
 *
 * What this is:
 *   - Honors the [LlmProvider] contract (including [completeWithTools])
 *     so the rest of the app (router, orchestrator, chat repository, UI)
 *     can be built and exercised end-to-end.
 *   - Produces plausible-looking canned responses so the chat UI has
 *     something to render in screenshots and demos.
 *   - In tool-capable mode, synthesizes a [ToolCall] when the user's
 *     prompt contains a trigger phrase that maps to a known tool
 *     ("what time is it" → get_current_datetime, "calculate X" →
 *     calculator, "remember that" → notes/remember). This lets the full
 *     function-calling round-trip be demoed without a real model.
 *
 * Phase 4 additions:
 *   - Subscribes to [MemoryPressureMonitor]. On CRITICAL state, the
 *     (mock) model is unloaded and [isAvailable] returns false until
 *     pressure subsides — implementing the "tiered memory shedding
 *     strategy" from Section 5.2 of the plan.
 *   - Lazy load + idle unload: the model is loaded on first use and
 *     unloaded after [UserSettings.idleUnloadMinutes] of inactivity
 *     (per Section 5.4 "Battery Optimization").
 *
 * What replaces it in Phase 3.x:
 *   - MLC-LLM (or llama.cpp) Android bindings loading a 4-bit quantized
 *     model into the Snapdragon 8 Gen 3 NPU via the Qualcomm AI Engine
 *     Direct SDK.
 *   - The public contract stays identical; only the bodies change.
 *
 * See Section 5.1 of the technical plan for the production design.
 */
@Singleton
class OnDeviceLlmProvider @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val settings: SettingsRepository,
    private val memoryMonitor: MemoryPressureMonitor,
) : LlmProvider {

    override val name: String = "Hermes-OnDevice-Mock"
    override val isOnDevice: Boolean = true
    override val model: String = "hermes-3-8b-q4f16"

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var lastUsedAt: Long = 0L

    private val scope = CoroutineScope(Dispatchers.Default)
    private var pressureJob: Job? = null
    private var idleJob: Job? = null

    init {
        // Subscribe to memory pressure transitions.
        pressureJob = scope.launch {
            memoryMonitor.state.collect { state ->
                when (state) {
                    MemoryPressureState.CRITICAL -> shedLoad()
                    MemoryPressureState.NORMAL -> { /* allow reload on next use */ }
                    MemoryPressureState.ELEVATED -> { /* no-op; warn only */ }
                }
            }
        }
    }

    /**
     * Shed the (mock) model from memory. Called when the system reports
     * CRITICAL memory pressure. The next [isAvailable] call will reload
     * the model only if pressure has subsided.
     */
    private suspend fun shedLoad() {
        if (!loaded) return
        Timber.tag("OnDeviceLlm").w("memory pressure CRITICAL — shedding model")
        loaded = false
        // Phase 3.x: also flush MLC-LLM weights to flash storage here.
    }

    override suspend fun isAvailable(): Boolean {
        val s = settings.current()
        if (!s.onDeviceEnabled) return false

        // Don't reload while memory pressure is CRITICAL.
        if (memoryMonitor.state.value == MemoryPressureState.CRITICAL) {
            return false
        }

        if (!loaded) {
            delay(150)
            loaded = true
            Timber.tag("OnDeviceLlm").i("Mock model loaded: $model")
        }
        markUsed()
        return true
    }

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
        val reply = cannedReplyFor(messages)
        return LlmResponse(
            content = reply,
            tokensUsed = (reply.length / 4).coerceAtLeast(1),
            model = model,
            finishReason = "stop",
        )
    }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()

        // Synthesize a tool call if a trigger phrase matches.
        val toolCall = synthesizeToolCall(lastUser, tools)
        if (toolCall != null) {
            return LlmToolResponse(
                content = "",
                toolCalls = listOf(toolCall),
                tokensUsed = 8,
                model = model,
                finishReason = "tool_calls",
            )
        }

        // Otherwise behave like the non-tool path.
        val reply = cannedReplyFor(messages)
        return LlmToolResponse(
            content = reply,
            toolCalls = emptyList(),
            tokensUsed = (reply.length / 4).coerceAtLeast(1),
            model = model,
            finishReason = "stop",
        )
    }

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        val reply = cannedReplyFor(messages)
        val tokens = reply.split(" ").map { if (it.endsWith('\n')) it else "$it " }
        for (tok in tokens) {
            delay(20L + Random.nextLong(60L))
            emit(LlmStreamChunk.Delta(tok))
        }
        emit(LlmStreamChunk.Done)
    }.flowOn(dispatchers.default)

    // --- canned-reply generator (unchanged from Phase 1) ---

    private fun cannedReplyFor(messages: List<LlmMessage>): String {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val seed = lastUser.hashCode()
        val r = Random(seed)

        val opener = when {
            lastUser.isBlank() -> "I'm Hermes — what can I help with?"
            lastUser.endsWith("?") -> pick(r, listOf(
                "Good question. ",
                "Let me think about that. ",
                "Sure — here's what I'd suggest. ",
            ))
            lastUser.length > 200 -> "Here's a more considered take. "
            else -> pick(r, listOf("Got it. ", "Understood. ", "Sure. "))
        }

        val echo = if (lastUser.isNotBlank()) {
            "You said: \"${lastUser.take(140)}\".\n"
        } else ""

        return "[on-device mock] $opener\n$echo" +
            "In the production build, a 4-bit quantized Hermes-3 8B model would " +
            "generate a real reply here via the Snapdragon NPU. " +
            "The streaming cadence you're seeing simulates per-token latency."
    }

    private fun <T> pick(r: Random, items: List<T>): T = items[r.nextInt(items.size)]

    // --- Phase 2 tool-call synthesis ---

    /**
     * Map a free-text user prompt to a synthetic [ToolCall] when one of a
     * small set of trigger phrases matches. Returns null when the prompt
     * doesn't look tool-shaped, in which case the provider emits a normal
     * text reply.
     *
     * In Phase 3 the model itself emits tool calls; this heuristic is
     * removed entirely.
     */
    private fun synthesizeToolCall(
        userPrompt: String,
        availableTools: List<ToolDescriptor>,
    ): ToolCall? {
        val p = userPrompt.lowercase()
        val byName = availableTools.associateBy { it.name }

        // 1. Datetime trigger
        if (byName.containsKey("get_current_datetime") &&
            (p.contains("what time") || p.contains("what day") || p.contains("today's date") ||
                p.contains("what's the date"))) {
            return ToolCall(
                id = "call_${System.currentTimeMillis()}",
                name = "get_current_datetime",
                arguments = mapOf("component" to JsonPrimitive("datetime")),
            )
        }

        // 2. Calculator trigger
        if (byName.containsKey("calculator")) {
            val expr = Regex("""(?:calculate|compute|what(?:'s| is))\s+(.+)""").find(p)
                ?.groupValues?.getOrNull(1)
                ?.trim()
                ?.removeSuffix("?")
                ?.takeIf { it.matches(Regex("""[\d\s+\-*/().]+""")) && it.any { c -> c.isDigit() } }
            if (expr != null) {
                return ToolCall(
                    id = "call_${System.currentTimeMillis()}",
                    name = "calculator",
                    arguments = mapOf("expression" to JsonPrimitive(expr)),
                )
            }
        }

        // 3. Notes/remember trigger
        if (byName.containsKey("notes") && p.contains("remember that")) {
            val fact = userPrompt.substringAfter("remember that").trim().removeSuffix(".")
            if (fact.isNotBlank()) {
                return ToolCall(
                    id = "call_${System.currentTimeMillis()}",
                    name = "notes",
                    arguments = mapOf(
                        "action" to JsonPrimitive("remember"),
                        "content" to JsonPrimitive(fact),
                    ),
                )
            }
        }

        return null
    }

    // --- Phase 4: idle unload ---

    /**
     * Mark the model as just-used and (re)schedule the idle-unload task.
     *
     * Per Section 5.4 of the plan: "The on-device LLM model is loaded
     * lazily and unloaded after a configurable idle period (default: 5
     * minutes of no agent interaction), reducing standby power
     * consumption to near-zero."
     */
    private fun markUsed() {
        lastUsedAt = System.currentTimeMillis()
        idleJob?.cancel()
        val scope = scope
        idleJob = scope.launch {
            val idleMs = settings.current().idleUnloadMinutes * 60_000L
            delay(idleMs)
            if (loaded && System.currentTimeMillis() - lastUsedAt >= idleMs) {
                Timber.tag("OnDeviceLlm").i("idle for ${idleMs / 60_000}min — unloading model")
                loaded = false
            }
        }
    }
}
