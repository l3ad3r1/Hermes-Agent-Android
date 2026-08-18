package com.hermes.agent.data.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * The Phase-3 [EmbeddingService]: real on-device sentence embeddings via ONNX
 * Runtime + all-MiniLM-L6-v2 (int8), replacing the [HashingEmbeddingService]
 * mock. 384-dim, mean-pooled over tokens (attention-masked) then L2-normalized,
 * so cosine similarity reduces to a dot product — identical public contract.
 *
 * The model (`model.onnx`) and tokenizer (`vocab.txt`) live in the shared
 * "AI Models/embeddings/all-MiniLM-L6-v2" folder — the same files the
 * Hermes model manager downloads, so a model already on the device
 * is reused with no extra download. Until both files exist we transparently fall
 * back to the deterministic hashing embedder, so the RAG/memory paths keep
 * working (and the test suite stays green) before the model is present.
 *
 * Inference is serialized behind a mutex — one OrtSession, one call at a time.
 */
@Singleton
class MiniLmEmbeddingService @Inject constructor(
    private val fallback: HashingEmbeddingService,
) : EmbeddingService {

    override val dimension: Int = 384

    private val maxTokens: Int = 256
    private val mutex = Mutex()

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: WordPieceTokenizer? = null
    @Volatile private var inputNames: Set<String> = emptySet()

    private fun modelFile(): File =
        File(Environment.getExternalStorageDirectory(), "$DIR/model.onnx")

    private fun vocabFile(): File =
        File(Environment.getExternalStorageDirectory(), "$DIR/vocab.txt")

    /** True once the model + vocab are on disk; otherwise callers get the fallback. */
    fun isReady(): Boolean = modelFile().exists() && vocabFile().exists()

    override suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        if (!isReady()) return@withContext fallback.embed(text)
        try {
            ensureLoaded()
            val enc = tokenizer!!.encode(text)
            mutex.withLock { runSession(enc) }
        } catch (t: Throwable) {
            // Never let an embedding failure break memory/RAG — degrade to the mock.
            Timber.tag("MiniLmEmbed").w(t, "ONNX embed failed; using fallback")
            fallback.embed(text)
        }
    }

    private suspend fun ensureLoaded() {
        if (session != null && tokenizer != null) return
        mutex.withLock {
            if (session != null && tokenizer != null) return
            val env = OrtEnvironment.getEnvironment()
            val s = env.createSession(modelFile().absolutePath, OrtSession.SessionOptions())
            inputNames = s.inputNames.toSet()
            tokenizer = WordPieceTokenizer(
                vocab = vocabFile().inputStream().use { WordPieceTokenizer.loadVocab(it) },
                maxTokens = maxTokens,
            )
            session = s
        }
    }

    private fun runSession(enc: WordPieceTokenizer.Encoding): FloatArray {
        val env = OrtEnvironment.getEnvironment()
        val s = session!!
        val seq = enc.size
        val shape = longArrayOf(1, seq.toLong())
        val tensors = HashMap<String, OnnxTensor>()
        try {
            fun put(name: String, data: LongArray) {
                if (name in inputNames) tensors[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
            }
            put("input_ids", enc.ids)
            put("attention_mask", enc.attentionMask)
            put("token_type_ids", LongArray(seq)) // single sequence → all zeros
            val result = s.run(tensors)
            try {
                val out = result[0] as OnnxTensor
                @Suppress("UNCHECKED_CAST")
                val hidden = (out.value as Array<Array<FloatArray>>)[0] // [seq][dim]
                return meanPool(hidden, enc.attentionMask)
            } finally {
                result.close()
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    /** Attention-masked mean pooling over token embeddings, then L2-normalize. */
    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden.firstOrNull()?.size ?: dimension
        val sum = FloatArray(dim)
        var count = 0f
        for (t in hidden.indices) {
            if (mask[t] == 0L) continue
            count += 1f
            val row = hidden[t]
            for (d in 0 until dim) sum[d] += row[d]
        }
        if (count > 0f) for (d in 0 until dim) sum[d] /= count
        var norm = 0.0
        for (f in sum) norm += (f * f).toDouble()
        val inv = sqrt(norm).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(sum.size) { sum[it] / inv }
    }

    private companion object {
        const val DIR = "AI Models/embeddings/all-MiniLM-L6-v2"
    }
}
