package com.hermes.agent.domain.rag

import com.hermes.agent.domain.rag.Chunk

/**
 * Pipeline that grounds LLM responses in the user's personal documents.
 *
 * Per Section 6.3 of the plan, the pipeline has three stages:
 *   1. **Ingestion**: documents are chunked into semantically meaningful
 *      segments, embedded, and stored in the local vector database.
 *   2. **Indexing**: chunks are indexed by both their embedding (for
 *      ANN search) and their raw text (for BM25 keyword search).
 *   3. **Retrieval**: at query time, the user's query is embedded and
 *      the database returns the top-k most similar chunks via a hybrid
 *      retrieval strategy that combines vector similarity with BM25.
 */
interface RagPipeline {

    /**
     * Ingest a document. Splits it into chunks, embeds each, and stores
     * everything in the local database. Returns the persisted [Document]
     * (with chunkCount populated).
     */
    suspend fun ingest(document: Document): Document

    /** Delete a document and all of its chunks. */
    suspend fun deleteDocument(documentId: String)

    /** Hot stream of all ingested documents, most-recent-first. */
    fun observeDocuments(): kotlinx.coroutines.flow.Flow<List<Document>>

    /**
     * Retrieve the top-k chunks most relevant to [query]. Combines
     * vector similarity with BM25 keyword search and reranks the
     * results.
     */
    suspend fun retrieve(query: String, limit: Int = 5): List<RetrievedChunk>

    /**
     * Build a context-window string suitable for splicing into an LLM
     * prompt. Each chunk is rendered as:
     *
     *   [doc=<title> | chunk=<ordinal+1>/<chunkCount>]
     *   <chunk text>
     *
     * Chunks are separated by a blank line. The total length is
     * capped at [maxChars].
     */
    suspend fun buildContext(query: String, maxChars: Int = 4_000): String
}
