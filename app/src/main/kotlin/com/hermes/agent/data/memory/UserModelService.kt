package com.hermes.agent.data.memory

import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Honcho-style dialectic user modelling.
 *
 * Maintains a prose "user model" — a short paragraph describing who the
 * user is, their personality, preferences, and communication style —
 * rebuilt from accumulated memories every [UPDATE_EVERY_N] conversations.
 *
 * The model is stored as a special memory entry prefixed with [MODEL_PREFIX]
 * so it lives in the same store but is filtered out of regular memory
 * recall and injected separately into system prompts by [OrchestratorImpl].
 *
 * Dialectic loop:
 *   1. After N conversations, [onConversationComplete] triggers [rebuild].
 *   2. [rebuild] reads all non-model memories and asks the LLM to synthesise
 *      them into a profile paragraph.
 *   3. The old model entry is deleted and the new one is saved.
 *   4. [OrchestratorImpl] reads [currentModel] to inject into prompts.
 */
@Singleton
class UserModelService @Inject constructor(
    private val llmProvider: CloudLlmProvider,
    private val memoryRepository: MemoryRepository,
    private val learningState: LearningState,
    private val dispatchers: DispatcherProvider,
) {

    /**
     * Records a completed conversation and rebuilds the user model once at least
     * [UPDATE_EVERY_N] conversations have happened since the last rebuild.
     *
     * The count is persisted (survives process death) so the trigger advances
     * across sessions, and the "last rebuilt at" marker is only updated when a
     * rebuild actually succeeds — so a rebuild skipped because the cloud was
     * unavailable is retried on the next conversation rather than lost.
     */
    suspend fun onConversationComplete() {
        val count = runCatching { learningState.incrementConversationCount() }.getOrNull() ?: return
        val lastRebuiltAt = runCatching { learningState.userModelRebuiltAt() }.getOrDefault(0)
        if (count - lastRebuiltAt >= UPDATE_EVERY_N) {
            if (rebuild()) {
                runCatching { learningState.setUserModelRebuiltAt(count) }
            }
        }
    }

    /**
     * Returns the current model text, or null if not yet built.
     * Strips the [MODEL_PREFIX] before returning.
     */
    suspend fun currentModel(): String? = withContext(dispatchers.io) {
        runCatching {
            memoryRepository.searchMemories("", limit = 200)
                .firstOrNull { it.content.startsWith(MODEL_PREFIX) }
                ?.content
                ?.removePrefix(MODEL_PREFIX)
                ?.trim()
        }.getOrNull()
    }

    /** Rebuilds and persists the user model. Returns true only if a new model was saved. */
    private suspend fun rebuild(): Boolean = withContext(dispatchers.io) {
        if (!llmProvider.isAvailable()) return@withContext false

        val facts = runCatching {
            memoryRepository.searchMemories("", limit = 200)
                .filter { !it.content.startsWith(MODEL_PREFIX) }
                .map { it.content }
        }.getOrDefault(emptyList())

        if (facts.size < 3) return@withContext false

        val factList = facts.take(50).joinToString("\n") { "- $it" }
        val response = runCatching {
            llmProvider.complete(
                listOf(
                    LlmMessage(role = "system", content = MODEL_SYSTEM),
                    LlmMessage(role = "user", content = "Known facts:\n$factList"),
                )
            )
        }.onFailure { Timber.tag("UserModel").w(it, "model rebuild failed") }
            .getOrNull() ?: return@withContext false

        val newModel = response.content.trim()
        if (newModel.isBlank() || newModel.length < 20) return@withContext false

        // Delete old model entry.
        runCatching {
            memoryRepository.searchMemories("", limit = 200)
                .filter { it.content.startsWith(MODEL_PREFIX) }
                .forEach { memoryRepository.deleteMemory(it.id) }
        }

        // Save new model.
        runCatching { memoryRepository.addMemory("$MODEL_PREFIX$newModel") }
            .onSuccess { Timber.tag("UserModel").i("user model rebuilt (${facts.size} facts)") }
            .onFailure { Timber.tag("UserModel").w(it, "model save failed") }
            .isSuccess
    }

    companion object {
        const val MODEL_PREFIX = "[USER_MODEL] "
        const val UPDATE_EVERY_N = 5

        private val MODEL_SYSTEM = """
            You are a user modelling assistant. Given a list of known facts about a person,
            write a brief 2-3 sentence personality and preference profile of this person.
            Write in third person as if briefing an AI assistant about who they will be helping.
            Focus on: who they are, what they care about, how they prefer to communicate.
            Example: "Rinu is a software engineer based in India who values efficiency and
            directness. They are technically sophisticated and prefer concise, actionable
            responses over lengthy explanations. They work on Android apps and AI projects."
            Be accurate — only include what the facts support.
        """.trimIndent()
    }
}
