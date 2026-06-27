package com.hermes.agent.data.memory

/**
 * Contract for embedding text into a fixed-dimensional float vector.
 *
 * Phase 2 ships a deterministic mock implementation that hashes the text
 * into a 384-dim pseudo-vector — enough to exercise the vector store
 * and similarity-search code paths end-to-end without loading a real
 * embedding model.
 *
 * Phase 3 will swap in the on-device all-MiniLM-L6-v2 quantized model
 * via the MLC-LLM runtime (or a separate ONNX-RT delegate). The public
 * contract stays identical.
 *
 * Per Section 6.3 of the plan: "Each chunk is embedded using a small
 * on-device embedding model (such as all-MiniLM-L6-v2 quantized)."
 */
interface EmbeddingService {

    /** Dimensionality of the vectors produced by [embed]. */
    val dimension: Int

    /**
     * Embed a single text into a fixed-length float vector.
     * The returned vector is L2-normalized so cosine similarity reduces
     * to a dot product.
     */
    suspend fun embed(text: String): FloatArray

    /** Batch variant — defaults to sequential embedding. */
    suspend fun embedAll(texts: List<String>): List<FloatArray> =
        texts.map { embed(it) }
}
