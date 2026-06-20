package com.hermes.agent.data.rag

/**
 * Recursive text splitter inspired by LangChain's
 * `RecursiveCharacterTextSplitter`.
 *
 * Per Section 6.3 of the plan: "documents are chunked into semantically
 * meaningful segments using a recursive text splitter that respects
 * paragraph and section boundaries."
 *
 * Strategy: try to split on the most meaningful separator first. If the
 * resulting pieces still exceed [chunkSize], recurse with the next
 * separator. Adjacent small pieces are merged back up to [chunkSize]
 * with [chunkOverlap] of overlap.
 *
 * Separator hierarchy (most → least meaningful):
 *   1. `\n\n` (paragraph break)
 *   2. `\n`   (line break)
 *   3. `. `   (sentence boundary)
 *   4. ` `    (word boundary)
 *   5. ``     (character — last resort)
 */
class DocumentChunker(
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
) {

    /** Split [text] into a list of chunk strings. */
    fun split(text: String): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val raw = recursiveSplit(text, SEPARATORS)
        return mergeWithOverlap(raw)
    }

    private fun recursiveSplit(text: String, separators: List<String>): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        if (separators.isEmpty()) return text.chunked(chunkSize)

        val separator = separators.first()
        val rest = separators.drop(1)

        // Split on the current separator; if it's empty, fall back to chunked.
        val pieces = if (separator.isEmpty()) {
            text.chunked(chunkSize / 2)
        } else {
            text.split(separator)
        }

        val out = mutableListOf<String>()
        for (piece in pieces) {
            if (piece.length <= chunkSize) {
                if (piece.isNotBlank()) out.add(piece)
            } else {
                out.addAll(recursiveSplit(piece, rest))
            }
        }
        return out
    }

    private fun mergeWithOverlap(pieces: List<String>): List<String> {
        if (pieces.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var current = StringBuilder()
        var currentLen = 0

        for (piece in pieces) {
            val pieceLen = piece.length
            if (currentLen + pieceLen + 1 <= chunkSize) {
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
                currentLen += pieceLen + 1
            } else {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    // Carry over the last `chunkOverlap` chars as overlap.
                    val tail = current.takeLast(chunkOverlap)
                    current = StringBuilder(tail)
                    currentLen = tail.length
                }
                current.append(piece)
                currentLen += pieceLen
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 800
        const val DEFAULT_CHUNK_OVERLAP = 100
        private val SEPARATORS = listOf("\n\n", "\n", ". ", " ", "")
    }
}
