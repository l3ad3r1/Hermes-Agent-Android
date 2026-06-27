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
 * Real-time fact extractor — the first half of the closed learning loop.
 *
 * Called after every Orchestrator turn. Sends the user message + agent
 * reply to the LLM with a tight extraction prompt and persists any new
 * personal facts into [MemoryRepository].
 *
 * Design choices:
 * - Uses [CloudLlmProvider.complete] directly (not via the router) so
 *   it is always the configured cloud model. If the cloud is not
 *   available the call is silently skipped — learning is best-effort.
 * - Duplicate guard: a new fact is skipped if any existing memory
 *   contains 80 %+ of its words (simple set-intersection check).
 * - Fire-and-forget: callers launch this in a supervisor scope; errors
 *   are caught and logged, never propagated.
 */
@Singleton
class ConversationLearner @Inject constructor(
    private val llmProvider: CloudLlmProvider,
    private val memoryRepository: MemoryRepository,
    private val dispatchers: DispatcherProvider,
) {

    suspend fun extractAndLearn(
        userMessage: String,
        agentReply: String,
    ) = withContext(dispatchers.io) {
        if (!llmProvider.isAvailable()) return@withContext

        val response = runCatching {
            llmProvider.complete(
                listOf(
                    LlmMessage(role = "system", content = EXTRACTION_SYSTEM),
                    LlmMessage(
                        role = "user",
                        content = "User: ${userMessage.take(600)}\nAssistant: ${agentReply.take(600)}",
                    ),
                )
            )
        }.onFailure { Timber.tag("ConversationLearner").w(it, "extraction failed") }
            .getOrNull() ?: return@withContext

        val raw = response.content.trim()
        if (raw == "NONE" || raw.isBlank()) return@withContext

        val existingContents = runCatching {
            memoryRepository.searchMemories("", limit = 200).map { it.content.lowercase() }
        }.getOrDefault(emptyList())

        raw.lines()
            .map { it.removePrefix("- ").trim() }
            .filter { it.length > 8 && it.isNotBlank() }
            .filterNot { fact -> isDuplicate(fact, existingContents) }
            .take(5)
            .forEach { fact ->
                runCatching { memoryRepository.addMemory(fact) }
                    .onSuccess { Timber.tag("ConversationLearner").d("learned: $fact") }
                    .onFailure { Timber.tag("ConversationLearner").w(it, "save failed") }
            }
    }

    private fun isDuplicate(fact: String, existingContents: List<String>): Boolean {
        val factWords = fact.lowercase().split(" ").filter { it.length > 3 }.toSet()
        if (factWords.isEmpty()) return false
        return existingContents.any { existing ->
            val existingWords = existing.split(" ").filter { it.length > 3 }.toSet()
            val overlap = factWords.intersect(existingWords).size
            overlap.toFloat() / factWords.size >= 0.75f
        }
    }

    companion object {
        private val EXTRACTION_SYSTEM = """
            Extract personal facts about the USER (not the assistant) from this conversation.
            Only extract EXPLICIT, STATED facts: name, age, location, job/career, family members,
            preferences, hobbies, goals, skills, health, important dates, opinions.
            Skip: general questions, task requests, facts about third parties, agent responses.
            Format: one fact per line, no bullet points, no numbering.
            Examples of good facts:
              User's name is Rinu
              User works as a software engineer
              User prefers dark mode
              User lives in India
            If nothing personal was shared, respond with exactly: NONE
        """.trimIndent()
    }
}
