package com.hermes.agent.domain.model

/**
 * A long-term semantic memory entry.
 *
 * Implements the long-term store described in Section 6.2 of the plan.
 * Short-term episodic memory is implicitly represented by the recent
 * [Message]s of the active conversation and is not modeled as a separate
 * type.
 *
 * In Phase 1 the [embedding] field is always null: the persistence layer
 * stores the textual content but vector indexing is stubbed. Phase 2 will
 * populate embeddings via the on-device embedding model
 * (all-MiniLM-L6-v2 quantized) and store them in the SQLite-VSS extension.
 *
 * @property id Stable unique identifier (UUID).
 * @property content Natural-language summary of the remembered fact.
 * @property embedding Optional 384-dim float vector (null in Phase 1).
 * @property relevanceScore Recency-weighted relevance; used by the
 *   consolidation pruner.
 * @property createdAt Wall-clock millis when the memory was first written.
 * @property lastAccessedAt Wall-clock millis of the most recent retrieval.
 * @property accessCount Number of times this memory has been retrieved.
 */
data class Memory(
    val id: String,
    val content: String,
    val embedding: List<Float>? = null,
    val relevanceScore: Float = 0f,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0,
)
