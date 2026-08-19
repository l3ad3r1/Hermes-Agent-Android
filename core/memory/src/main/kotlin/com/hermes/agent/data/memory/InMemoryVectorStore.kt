package com.hermes.agent.data.memory

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Thread-safe in-memory [VectorStore] backed by a [ConcurrentHashMap].
 *
 * Similarity search is brute-force cosine similarity, which (because
 * [HashingEmbeddingService] L2-normalizes its outputs) reduces to a
 * dot product. For a 384-dim vector and a few thousand entries this
 * is sub-millisecond on modern Android devices.
 */
@Singleton
class InMemoryVectorStore @Inject constructor() : VectorStore {

    private val entries = ConcurrentHashMap<String, VectorEntry>()

    override suspend fun upsert(entry: VectorEntry) {
        entries[entry.id] = entry
    }

    override suspend fun upsertAll(newEntries: List<VectorEntry>) {
        for (e in newEntries) entries[e.id] = e
    }

    override suspend fun delete(id: String) {
        entries.remove(id)
    }

    override suspend fun search(query: FloatArray, limit: Int): List<VectorSearchResult> {
        if (entries.isEmpty()) return emptyList()
        val qNorm = l2Normalize(query)
        val scored = entries.values.map { entry ->
            VectorSearchResult(
                entry = entry,
                score = cosine(qNorm, entry.vector),
            )
        }
        return scored.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun count(): Int = entries.size

    override suspend fun clear() {
        entries.clear()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "vector dimension mismatch: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += (a[i] * b[i]).toDouble()
            na += (a[i] * a[i]).toDouble()
            nb += (b[i] * b[i]).toDouble()
        }
        val denom = (sqrt(na) * sqrt(nb)).toFloat().coerceAtLeast(1e-6f)
        return (dot / denom).toFloat()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (f in v) sum += (f * f).toDouble()
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
