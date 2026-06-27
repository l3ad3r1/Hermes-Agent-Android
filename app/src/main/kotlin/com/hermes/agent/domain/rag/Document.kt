package com.hermes.agent.domain.rag

/**
 * A user-supplied document ingested into the RAG pipeline.
 *
 * @property id Stable unique identifier (UUID).
 * @property title Human-readable title; used in citations.
 * @property sourceUri Where the document came from. For Phase 2 this is
 *   typically a `file://` URI for a picked PDF/txt, or a `content://`
 *   URI for a shared intent.
 * @property mimeType Original MIME type (`application/pdf`, `text/plain`,
 *   `text/html`). The chunker uses this to pick a parser.
 * @property content Extracted plain-text content.
 * @property createdAt Wall-clock millis when the document was ingested.
 * @property chunkCount Number of chunks the document was split into.
 */
data class Document(
    val id: String,
    val title: String,
    val sourceUri: String,
    val mimeType: String,
    val content: String,
    val createdAt: Long,
    val chunkCount: Int = 0,
)

/**
 * A semantically meaningful segment of a [Document].
 *
 * Per Section 6.3 of the plan: "documents are chunked into semantically
 * meaningful segments using a recursive text splitter that respects
 * paragraph and section boundaries."
 *
 * @property id Stable unique identifier (UUID).
 * @property documentId Owning document.
 * @property ordinal 0-based position within the document.
 * @property text Chunk text.
 * @property embedding 384-dim float vector (null in Phase 2 if the
 *   embedding service was unavailable at ingest time).
 * @property tokenCount Approximate token count (1 token ≈ 4 chars).
 */
data class Chunk(
    val id: String,
    val documentId: String,
    val ordinal: Int,
    val text: String,
    val embedding: List<Float>? = null,
    val tokenCount: Int = 0,
)

/**
 * A single retrieved chunk with its relevance score.
 */
data class RetrievedChunk(
    val chunk: Chunk,
    val document: Document,
    /** Combined BM25 + vector score in [0, 1]; higher is more relevant. */
    val score: Float,
    /** Which retrieval method contributed to this result. */
    val source: RetrievalSource,
)

enum class RetrievalSource { VECTOR, KEYWORD, HYBRID }
