package com.hermes.agent.data.rag

import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.entity.DocumentChunkEntity
import com.hermes.agent.data.local.entity.DocumentEntity
import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.data.memory.VectorEntry
import com.hermes.agent.data.memory.VectorStore
import com.hermes.agent.domain.rag.Document
import com.hermes.agent.domain.rag.RagPipeline
import com.hermes.agent.domain.rag.RetrievedChunk
import com.hermes.agent.domain.rag.RetrievalSource
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2 [RagPipeline] implementation.
 *
 * Wiring:
 *   - [DocumentDao] / [DocumentChunkDao] for persistence.
 *   - [DocumentChunker] for recursive text splitting.
 *   - [EmbeddingService] for chunk embeddings.
 *   - [VectorStore] for in-memory ANN search (keyed by chunk id).
 *   - [Bm25Scorer] for keyword-based retrieval.
 *
 * Retrieval strategy:
 *   1. Embed the query.
 *   2. Run vector search → top-k chunks with cosine similarity.
 *   3. Run BM25 search → top-k chunks with keyword score.
 *   4. Merge by chunk id, taking the max score from either source.
 *      Tag the merged result with [RetrievalSource.HYBRID] if both
 *      contributed, otherwise [VECTOR] or [KEYWORD].
 *   5. Sort by combined score, take top-k.
 *
 * The vector store is in-memory; on cold start the pipeline lazily
 * rehydrates it from Room (chunk text only — embeddings are recomputed
 * on demand).
 */
@Singleton
class RagPipelineImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val chunkDao: DocumentChunkDao,
    private val embeddingService: EmbeddingService,
    private val vectorStore: VectorStore,
    private val dispatchers: DispatcherProvider,
) : RagPipeline {

    private val chunker = DocumentChunker()
    private val bm25 = Bm25Scorer()

    @Volatile
    private var indexHydrated = false

    override suspend fun ingest(document: Document): Document = withContext(dispatchers.io) {
        ensureIndexHydrated()

        // 1. Split.
        val chunkTexts = chunker.split(document.content)
        val now = System.currentTimeMillis()

        // 2. Embed each chunk.
        val embeddings = runCatching { embeddingService.embedAll(chunkTexts) }
            .onFailure { Timber.tag("RagPipeline").w(it, "batch embed failed") }
            .getOrDefault(List(chunkTexts.size) { FloatArray(embeddingService.dimension) })

        // 3. Persist document + chunks.
        val docEntity = DocumentEntity(
            id = document.id,
            title = document.title,
            sourceUri = document.sourceUri,
            mimeType = document.mimeType,
            content = document.content,
            createdAt = if (document.createdAt > 0) document.createdAt else now,
            chunkCount = chunkTexts.size,
        )
        documentDao.upsert(docEntity)

        val chunkEntities = chunkTexts.mapIndexed { i, text ->
            DocumentChunkEntity(
                id = IdGenerator.newId(),
                documentId = document.id,
                ordinal = i,
                text = text,
                embedding = null, // Phase 2: vectors live in VectorStore only.
                tokenCount = (text.length / 4).coerceAtLeast(1),
            )
        }
        chunkDao.upsertAll(chunkEntities)

        // 4. Index: vector store + BM25.
        chunkEntities.forEachIndexed { i, entity ->
            vectorStore.upsert(
                VectorEntry(
                    id = entity.id,
                    vector = embeddings[i],
                    payload = entity.text,
                )
            )
            bm25.addDocument(entity.id, entity.text)
        }

        Timber.tag("RagPipeline").i(
            "ingested doc=%s chunks=%d", document.id.take(8), chunkEntities.size,
        )

        document.copy(chunkCount = chunkEntities.size)
    }

    override suspend fun deleteDocument(documentId: String) = withContext(dispatchers.io) {
        val chunks = chunkDao.getByDocument(documentId)
        for (chunk in chunks) {
            vectorStore.delete(chunk.id)
            bm25.removeDocument(chunk.id)
        }
        chunkDao.deleteByDocument(documentId)
        documentDao.delete(documentId)
        Timber.tag("RagPipeline").i("deleted doc=%s chunks=%d", documentId.take(8), chunks.size)
    }

    override fun observeDocuments(): Flow<List<Document>> =
        documentDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun retrieve(query: String, limit: Int): List<RetrievedChunk> =
        withContext(dispatchers.io) {
            ensureIndexHydrated()

            val queryVec = runCatching { embeddingService.embed(query) }
                .onFailure { Timber.tag("RagPipeline").w(it, "query embed failed") }
                .getOrNull()
                ?: return@withContext emptyList()

            val vectorHits = vectorStore.search(queryVec, limit = limit)
                .associateBy { it.entry.id }
                .mapValues { it.value.score }

            val keywordHits = bm25.search(query, limit = limit).toMap()

            // Merge by chunk id.
            val allIds = vectorHits.keys + keywordHits.keys
            val merged = allIds.map { id ->
                val vScore = vectorHits[id] ?: 0f
                val kScore = keywordHits[id]?.toFloat() ?: 0f
                val source = when {
                    vScore > 0f && kScore > 0f -> RetrievalSource.HYBRID
                    vScore > 0f -> RetrievalSource.VECTOR
                    else -> RetrievalSource.KEYWORD
                }
                // Normalize: vector score is in [-1, 1]; keyword score is
                // unbounded. Use a weighted combination favoring vector
                // for semantic queries, keyword for exact matches.
                val combined = 0.7f * vScore.coerceIn(0f, 1f) +
                    0.3f * (kScore / (kScore + 1f)).coerceIn(0f, 1f)
                id to (combined to source)
            }

            merged.sortedByDescending { it.second.first }.take(limit).mapNotNull { (id, pair) ->
                val (score, source) = pair
                val chunkEntity = chunkDao.getById(id) ?: return@mapNotNull null
                val docEntity = documentDao.getById(chunkEntity.documentId) ?: return@mapNotNull null
                RetrievedChunk(
                    chunk = com.hermes.agent.domain.rag.Chunk(
                        id = chunkEntity.id,
                        documentId = chunkEntity.documentId,
                        ordinal = chunkEntity.ordinal,
                        text = chunkEntity.text,
                        embedding = null,
                        tokenCount = chunkEntity.tokenCount,
                    ),
                    document = docEntity.toDomain(),
                    score = score,
                    source = source,
                )
            }
        }

    override suspend fun buildContext(query: String, maxChars: Int): String =
        withContext(dispatchers.io) {
            val retrieved = retrieve(query, limit = 10)
            if (retrieved.isEmpty()) return@withContext ""

            val sb = StringBuilder()
            for (item in retrieved) {
                val header = "[doc=${item.document.title} | " +
                    "chunk=${item.chunk.ordinal + 1}/${item.document.chunkCount} | " +
                    "score=${"%.2f".format(item.score)}]\n"
                val block = header + item.chunk.text + "\n\n"
                if (sb.length + block.length > maxChars) break
                sb.append(block)
            }
            sb.toString()
        }

    /**
     * Lazily hydrate the in-memory index from Room on cold start.
     *
     * Phase 3 will skip this entirely once embeddings are persisted in
     * the `embedding` BLOB column and the sqlite_vss virtual table is
     * the source of truth.
     */
    private suspend fun ensureIndexHydrated() {
        if (indexHydrated) return
        Timber.tag("RagPipeline").i("hydrating RAG index from Room…")
        val docs = documentDao.observeAll().first()
        for (doc in docs) {
            val chunks = chunkDao.getByDocument(doc.id)
            if (chunks.isEmpty()) continue
            // Recompute embeddings (Phase 2: not persisted).
            val embeddings = runCatching {
                embeddingService.embedAll(chunks.map { it.text })
            }.getOrDefault(List(chunks.size) { FloatArray(embeddingService.dimension) })
            chunks.forEachIndexed { i, chunk ->
                vectorStore.upsert(
                    VectorEntry(id = chunk.id, vector = embeddings[i], payload = chunk.text)
                )
                bm25.addDocument(chunk.id, chunk.text)
            }
        }
        indexHydrated = true
        Timber.tag("RagPipeline").i("RAG index hydrated: %d chunks", vectorStore.count())
    }
}

private fun DocumentEntity.toDomain() = Document(
    id = id,
    title = title,
    sourceUri = sourceUri,
    mimeType = mimeType,
    content = content,
    createdAt = createdAt,
    chunkCount = chunkCount,
)

