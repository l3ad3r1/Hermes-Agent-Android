package com.hermes.agent.data.rag

/**
 * Lightweight BM25 scorer for keyword-based retrieval.
 *
 * Standard Okapi BM25 with default parameters k1=1.5, b=0.75. The
 * corpus is held in memory as a list of tokenized documents; scoring
 * is a brute-force scan, which is fine for the few-thousand-chunk
 * scale Phase 2 expects.
 *
 * Phase 3 will replace this with a SQLite FTS5 virtual table so the
 * scorer is incremental and persists across process restarts.
 */
class Bm25Scorer(
    private val k1: Double = 1.5,
    private val b: Double = 0.75,
) {

    private data class IndexedDoc(val id: String, val tokens: List<String>, val tokenFreq: Map<String, Int>)

    private val docs = mutableListOf<IndexedDoc>()
    private val docFreq = mutableMapOf<String, Int>()  // term → # docs containing it
    private var avgDocLen: Double = 0.0

    /** Add a document to the corpus. */
    fun addDocument(id: String, text: String) {
        val tokens = tokenize(text)
        val freq = tokens.groupingBy { it }.eachCount()
        synchronized(this) {
            docs.add(IndexedDoc(id, tokens, freq))
            for (term in freq.keys) {
                docFreq[term] = (docFreq[term] ?: 0) + 1
            }
            recomputeAvgLen()
        }
    }

    fun removeDocument(id: String) {
        synchronized(this) {
            val removed = docs.firstOrNull { it.id == id } ?: return
            docs.remove(removed)
            for (term in removed.tokenFreq.keys) {
                val newCount = (docFreq[term] ?: 0) - 1
                if (newCount <= 0) docFreq.remove(term) else docFreq[term] = newCount
            }
            recomputeAvgLen()
        }
    }

    /** Score every document against [query]; return top-k (id, score). */
    fun search(query: String, limit: Int = 5): List<Pair<String, Double>> {
        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty() || docs.isEmpty()) return emptyList()
        val n = docs.size
        val scored = synchronized(this) {
            docs.map { doc ->
                var score = 0.0
                for (term in queryTerms) {
                    val df = docFreq[term] ?: 0
                    if (df == 0) continue
                    val idf = Math.log(1.0 + (n - df + 0.5) / (df + 0.5))
                    val tf = doc.tokenFreq[term] ?: 0
                    val denom = tf + k1 * (1 - b + b * doc.tokens.size / avgDocLen)
                    score += idf * (tf * (k1 + 1) / denom)
                }
                doc.id to score
            }
        }
        return scored.sortedByDescending { it.second }.take(limit)
    }

    private fun recomputeAvgLen() {
        avgDocLen = if (docs.isEmpty()) 0.0 else docs.sumOf { it.tokens.size }.toDouble() / docs.size
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .split(Regex("""[^a-z0-9\u4e00-\u9fff]+"""))
            .filter { it.isNotBlank() }
}
