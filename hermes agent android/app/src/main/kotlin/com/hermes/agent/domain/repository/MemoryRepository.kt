package com.hermes.agent.domain.repository

import com.hermes.agent.domain.model.Memory
import kotlinx.coroutines.flow.Flow

/**
 * Long-term semantic memory store.
 *
 * Implements the long-term store described in Section 6.2 of the plan.
 * Phase 1 supports manual creation, listing, and deletion. Vector-based
 * similarity search is stubbed (returns an empty list); Phase 2 will
 * populate it via the on-device embedding model and SQLite-VSS index.
 */
interface MemoryRepository {

    /** Hot stream of all memories, most-recently-created first. */
    fun observeMemories(): Flow<List<Memory>>

    /** Add a new memory. Returns its id. */
    suspend fun addMemory(content: String): String

    /** Delete a memory by id. */
    suspend fun deleteMemory(id: String)

    /**
     * Semantic search. Phase 1 returns an empty list (vector index not yet
     * wired). Phase 2 will embed the query and return top-k neighbors.
     */
    suspend fun searchMemories(query: String, limit: Int = 5): List<Memory>
}
