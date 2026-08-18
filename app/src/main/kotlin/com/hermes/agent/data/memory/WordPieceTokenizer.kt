package com.hermes.agent.data.memory

import java.io.BufferedReader
import java.io.InputStream
import java.text.Normalizer

/**
 * BERT-style WordPiece tokenizer (uncased) — what all-MiniLM-L6-v2 expects.
 *
 * Pure and deterministic (unit-tested with a tiny vocab). Pipeline:
 *   lowercase → strip accents → split on whitespace and punctuation →
 *   greedy longest-match WordPiece (continuations prefixed `##`) →
 *   wrap with [CLS] … [SEP], truncating to [maxTokens].
 *
 * Produces token ids plus an attention mask; token-type ids are all zero for a
 * single sequence and are supplied by the caller if the model needs them.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val maxTokens: Int = 256,
    private val unkToken: String = "[UNK]",
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
    private val maxCharsPerWord: Int = 100,
) {
    private val unkId = vocab[unkToken] ?: error("vocab missing $unkToken")
    private val clsId = vocab[clsToken] ?: error("vocab missing $clsToken")
    private val sepId = vocab[sepToken] ?: error("vocab missing $sepToken")

    data class Encoding(val ids: LongArray, val attentionMask: LongArray) {
        val size: Int get() = ids.size
    }

    fun encode(text: String): Encoding {
        val pieces = mutableListOf<Int>()
        pieces += clsId
        // Reserve room for [CLS] and [SEP].
        val budget = maxTokens - 2
        outer@ for (word in basicTokenize(text)) {
            for (id in wordPiece(word)) {
                if (pieces.size - 1 >= budget) break@outer // -1 for the leading [CLS]
                pieces += id
            }
        }
        pieces += sepId
        val ids = LongArray(pieces.size) { pieces[it].toLong() }
        val mask = LongArray(pieces.size) { 1L }
        return Encoding(ids, mask)
    }

    /** Lowercase, strip accents, split on whitespace and punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val stripped = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "") // drop combining accent marks
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.setLength(0) } }
        for (ch in stripped) {
            when {
                ch.isWhitespace() -> flush()
                isPunctuation(ch) -> { flush(); out += ch.toString() }
                else -> sb.append(ch)
            }
        }
        flush()
        return out
    }

    /** Greedy longest-match-first WordPiece for a single whitespace token. */
    private fun wordPiece(word: String): List<Int> {
        if (word.length > maxCharsPerWord) return listOf(unkId)
        val out = mutableListOf<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var curId: Int? = null
            while (start < end) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { curId = id; break }
                end--
            }
            if (curId == null) return listOf(unkId) // unmatchable char → whole word is [UNK]
            out += curId
            start = end
        }
        return out
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(ch)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    companion object {
        /** Load a HuggingFace-style `vocab.txt` (one token per line, id == line index). */
        fun loadVocab(input: InputStream): Map<String, Int> {
            val map = HashMap<String, Int>()
            input.bufferedReader().use { reader: BufferedReader ->
                var i = 0
                reader.forEachLine { line -> map[line.trim()] = i++ }
            }
            return map
        }
    }
}
