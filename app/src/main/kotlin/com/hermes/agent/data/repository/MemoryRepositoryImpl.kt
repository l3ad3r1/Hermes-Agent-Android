package com.hermes.agent.data.repository

import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.data.memory.VectorEntry
import com.hermes.agent.data.memory.VectorStore
import com.hermes.agent.domain.model.Memory
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2 [MemoryRepository] implementation.
 *
 * Adds vector-based similarity search over Phase 1's keyword LIKE search.
 * On every [addMemory], the content is embedded via [EmbeddingService]
 * and the resulting vector is registered in the [VectorStore] under the
 * memory id. [searchMemories] now embeds the query and does an ANN
 * lookup against the store; if the store is empty (cold start) it falls
 * back to the keyword LIKE query.
 *
 * The Room `embedding` BLOB column remains null in Phase 2 — the
 * VectorStore is the source of truth for embeddings in RAM. Phase 3
 * will persist embeddings to Room (and SQLite-VSS) so they survive
 * process restart.
 */
@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val dispatchers: DispatcherProvider,
) : MemoryRepository {

    override fun observeMemories(): Flow<List<Memory>> =
        memoryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun addMemory(content: String): String = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val id = IdGenerator.newId()

        // Embed and index.
        val vector = runCatching { embeddingService.embed(content) }
            .onFailure { Timber.tag("MemoryRepo").w(it, "embedding failed") }
            .getOrNull()
        if (vector != null) {
            vectorStore.upsert(VectorEntry(id = id, vector = vector, payload = content))
        }

        // Persist text + (null embedding blob in Phase 2).
        memoryDao.upsert(
            MemoryEntity(
                id = id,
                content = content,
                embedding = null,
                relevanceScore = 1.0f,
                createdAt = now,
                lastAccessedAt = now,
                accessCount = 0,
            )
        )
        id
    }

    override suspend fun deleteMemory(id: String) = withContext(dispatchers.io) {
        vectorStore.delete(id)
        memoryDao.delete(id)
        Unit
    }

    /**
     * Hybrid search: vector similarity first, keyword LIKE as a fallback
     * when the vector store is empty (cold start) or returns no matches.
     */
    override suspend fun searchMemories(query: String, limit: Int): List<Memory> =
        withContext(dispatchers.io) {
            if (vectorStore.count() == 0) {
                return@withContext keywordFallback(query, limit)
            }
            val queryVec = runCatching { embeddingService.embed(query) }
                .onFailure { Timber.tag("MemoryRepo").w(it, "query embed failed") }
                .getOrNull()
                ?: return@withContext keywordFallback(query, limit)

            val hits = vectorStore.search(queryVec, limit = limit)
            if (hits.isEmpty()) {
                return@withContext keywordFallback(query, limit)
            }

            // Hydrate from Room (we only store payload text in the vector
            // store; the full Memory row is in Room).
            hits.mapNotNull { hit ->
                memoryDao.getById(hit.entry.id)?.toDomain()?.copy(
                    relevanceScore = hit.score,
                    lastAccessedAt = System.currentTimeMillis(),
                )
            }
        }

    private suspend fun keywordFallback(query: String, limit: Int): List<Memory> {
        Timber.tag("MemoryRepo").d("vector store empty — keyword fallback")
        return memoryDao.keywordSearch(query, limit).map { it.toDomain() }
    }
}

private fun MemoryEntity.toDomain() = Memory(
    id = id,
    content = content,
    embedding = embedding?.let { FloatArray(it.size / 4).also { arr ->
        java.nio.ByteBuffer.wrap(it).asFloatBuffer().get(arr)
    }.toList() },
    relevanceScore = relevanceScore,
    createdAt = createdAt,
    lastAccessedAt = lastAccessedAt,
    accessCount = accessCount,
)
