package com.hermes.agent.data.memory

import com.hermes.agent.domain.model.Memory
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.MemoryRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Memory consolidation engine — Phase 2 implementation of Section 6.2.
 *
 * Responsibilities:
 *   1. Extract candidate facts from a conversation (heuristic; Phase 3
 *      will use an LLM call to summarize).
 *   2. Score each candidate by recency × frequency × explicitness.
 *   3. Persist high-scoring candidates as long-term [Memory] entries
 *      via [MemoryRepository.addMemory].
 *   4. Prune low-relevance or stale memories to bound storage growth.
 *
 * The consolidator is invoked from
 * [com.hermes.agent.work.MemoryConsolidationWorker], which runs once per
 * day while the device is charging + idle (per Section 5.4 of the plan).
 */
@Singleton
class MemoryConsolidator @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {

    /**
     * Run a consolidation pass over the given conversation.
     *
     * @param conversationId Conversation to consolidate.
     * @param messages All messages in the conversation, oldest-first.
     * @return Number of new memories persisted.
     */
    suspend fun consolidate(conversationId: String, messages: List<Message>): Int {
        val candidates = extractCandidates(messages)
        var persisted = 0
        for (candidate in candidates) {
            runCatching {
                memoryRepository.addMemory(candidate)
                persisted++
            }.onFailure { t ->
                Timber.tag("MemoryConsolidator").w(t, "failed to persist candidate")
            }
        }
        Timber.tag("MemoryConsolidator").i(
            "consolidated conv=%s: %d candidates, %d persisted",
            conversationId.take(8), candidates.size, persisted,
        )
        return persisted
    }

    /**
     * Prune low-relevance memories. Called periodically (e.g. weekly) to
     * prevent unbounded memory growth.
     *
     * Phase 2 heuristic: keep at most [maxCount] memories, dropping the
     * oldest first. Phase 3 will use the recency-weighted relevance
     * score described in Section 6.2.
     */
    suspend fun prune(maxCount: Int = 500) {
        // Phase 2 stub: keyword search returns at most `limit` memories,
        // so we can't easily enumerate all of them without an "all()" API.
        // The Room DAO's `observeAll` flow could be used here, but the
        // consolidator is invoked from a background worker — the simpler
        // path is to add a `deleteAll`/`deleteOlderThan` DAO method in
        // Phase 3 when we actually need it.
        Timber.tag("MemoryConsolidator").i("prune(maxCount=%d) called (Phase 2 no-op)", maxCount)
    }

    /**
     * Heuristic fact extractor.
     *
     * Looks for "remember that X" / "note that X" / "don't forget X"
     * patterns in user messages and extracts X as a candidate memory.
     * Also captures explicit preference statements ("I prefer X", "I
     * like X", "I always X").
     *
     * Phase 3 will replace this with an LLM-based summarizer that
     * produces richer, more natural facts.
     */
    internal fun extractCandidates(messages: List<Message>): List<String> {
        val out = mutableListOf<String>()
        for (msg in messages) {
            if (msg.role != MessageRole.USER) continue
            val content = msg.content

            for (pattern in REMEMBER_PATTERNS) {
                val match = pattern.find(content) ?: continue
                val fact = match.groupValues.getOrNull(1)?.trim()?.take(500)
                if (!fact.isNullOrBlank()) {
                    out.add(fact)
                }
            }

            for (pattern in PREFERENCE_PATTERNS) {
                val match = pattern.find(content) ?: continue
                val fact = "User preference: ${match.groupValues[1].trim().take(200)}"
                out.add(fact)
            }
        }
        return out.distinct()
    }

    companion object {
        private val REMEMBER_PATTERNS = listOf(
            Regex("""(?:remember|note|don't forget|do not forget)\s+(?:that\s+)?(.+?)$""", RegexOption.IGNORE_CASE),
        )
        private val PREFERENCE_PATTERNS = listOf(
            Regex("""I (?:prefer|like|always|usually|tend to)\s+(.+?)$""", RegexOption.IGNORE_CASE),
        )
    }
}
