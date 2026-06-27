package com.hermes.agent.data.memory

import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Phase 2 mock embedding service.
 *
 * Strategy: SHA-256 the input text, then derive a 384-dim float vector
 * by repeatedly hashing the previous digest with a counter and using
 * 4 bytes of each hash as a float in [-1, 1]. Finally L2-normalize.
 *
 * Why this is OK for Phase 2:
 *   - Deterministic: same input → same vector → reproducible tests.
 *   - Cheap: no native runtime, no model weights.
 *   - Non-trivial: semantically different inputs produce different
 *     vectors, so similarity-search code paths are exercised.
 *
 * Why this is NOT OK beyond Phase 2:
 *   - Hash-based vectors have no semantic relationship. Two texts with
 *     the same meaning produce orthogonal vectors. Phase 3 must replace
 *     this with a real embedding model.
 */
@Singleton
class HashingEmbeddingService @Inject constructor() : EmbeddingService {

    override val dimension: Int = 384

    override suspend fun embed(text: String): FloatArray {
        val out = FloatArray(dimension)
        var digest = sha256(text.toByteArray())
        var counter = 0
        var i = 0
        while (i < dimension) {
            // Take 4 bytes from the current digest as a float in [-1, 1].
            val offset = (i * 4) % digest.size
            val bits = ByteBuffer.wrap(digest, offset, 4).int
            out[i] = (bits.toFloat() / Int.MAX_VALUE.toFloat()).coerceIn(-1f, 1f)
            // Re-hash for the next batch when we exhaust the current digest.
            if ((i + 1) % 8 == 0) {
                counter++
                digest = sha256(digest + byteArrayOf(counter.toByte()))
            }
            i++
        }
        return l2Normalize(out)
    }

    private fun sha256(input: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(input)
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (f in v) sum += (f * f).toDouble()
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
