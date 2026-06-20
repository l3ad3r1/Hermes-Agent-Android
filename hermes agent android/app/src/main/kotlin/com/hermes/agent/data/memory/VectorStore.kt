package com.hermes.agent.data.memory

/**
 * A single entry in the in-memory vector store.
 *
 * Phase 2 keeps embeddings in RAM; Phase 3 will swap to SQLite-VSS
 * backed by the `embedding` BLOB column in the `memories` (or
 * `document_chunks`) Room tables.
 */
data class VectorEntry(
    val id: String,
    val vector: FloatArray,
    val payload: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorEntry) return false
        return id == other.id && payload == other.payload && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + payload.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

/**
 * Result of a similarity search.
 */
data class VectorSearchResult(
    val entry: VectorEntry,
    /** Cosine similarity in [-1, 1]; higher is more similar. */
    val score: Float,
)

/**
 * In-memory vector store with brute-force cosine similarity search.
 *
 * Phase 2 implementation. The brute-force scan is fine for a few
 * thousand entries — which is well beyond what Phase 2 workloads
 * produce. Phase 3 will swap to an HNSW index (via SQLite-VSS or
 * a pure-Kotlin hnswlib binding) once corpus sizes grow.
 */
interface VectorStore {

    suspend fun upsert(entry: VectorEntry)

    suspend fun upsertAll(entries: List<VectorEntry>)

    suspend fun delete(id: String)

    suspend fun search(query: FloatArray, limit: Int = 5): List<VectorSearchResult>

    suspend fun count(): Int

    suspend fun clear()
}
