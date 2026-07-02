package com.hermes.agent.data.rag

import com.hermes.agent.data.local.dao.DocumentChunkDao
import com.hermes.agent.data.local.dao.DocumentDao
import com.hermes.agent.data.local.entity.DocumentChunkEntity
import com.hermes.agent.data.local.entity.DocumentEntity
import com.hermes.agent.data.memory.EmbeddingService
import com.hermes.agent.data.memory.VectorStore
import com.hermes.agent.domain.rag.Document
import com.hermes.agent.util.DefaultDispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPipelineImplTest {

    private val dim = 16

    private fun fakeEmbeddingService() = object : EmbeddingService {
        override val dimension = dim
        override suspend fun embed(text: String): FloatArray {
            // Deterministic pseudo-embedding from text length so similar texts
            // produce similar vectors in tests.
            val seed = text.hashCode()
            val r = kotlin.random.Random(seed)
            val v = FloatArray(dim) { r.nextFloat() * 2f - 1f }
            val norm = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(dim) { v[it] / norm }
        }
    }

    private fun mockDaos(): Pair<DocumentDao, DocumentChunkDao> {
        // A bare relaxed mock's observeAll() returns a Flow that never emits,
        // which makes ensureIndexHydrated()'s .first() throw — stub it to an
        // empty library by default; tests can re-stub as needed.
        val docDao = mockk<DocumentDao>(relaxed = true)
        every { docDao.observeAll() } returns flowOf(emptyList())
        return Pair(docDao, mockk<DocumentChunkDao>(relaxed = true))
    }

    @Test
    fun `ingest splits document into chunks and indexes them`() = runTest {
        val (docDao, chunkDao) = mockDaos()
        val pipeline = RagPipelineImpl(
            documentDao = docDao,
            chunkDao = chunkDao,
            embeddingService = fakeEmbeddingService(),
            vectorStore = com.hermes.agent.data.memory.InMemoryVectorStore(),
            dispatchers = DefaultDispatcherProvider(),
        )

        val doc = Document(
            id = "d1",
            title = "Test",
            sourceUri = "manual://test",
            mimeType = "text/plain",
            content = "This is paragraph one.\n\nThis is paragraph two.\n\nThis is paragraph three.",
            createdAt = 0L,
        )

        val result = pipeline.ingest(doc)
        assertTrue("should have at least 1 chunk", result.chunkCount >= 1)
    }

    @Test
    fun `retrieve returns empty when no documents ingested`() = runTest {
        val (docDao, chunkDao) = mockDaos()
        coEvery { docDao.observeAll() } returns flowOf(emptyList())

        val pipeline = RagPipelineImpl(
            documentDao = docDao,
            chunkDao = chunkDao,
            embeddingService = fakeEmbeddingService(),
            vectorStore = com.hermes.agent.data.memory.InMemoryVectorStore(),
            dispatchers = DefaultDispatcherProvider(),
        )

        val results = pipeline.retrieve("anything")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `buildContext returns empty string when nothing is indexed`() = runTest {
        val (docDao, chunkDao) = mockDaos()
        coEvery { docDao.observeAll() } returns flowOf(emptyList())

        val pipeline = RagPipelineImpl(
            documentDao = docDao,
            chunkDao = chunkDao,
            embeddingService = fakeEmbeddingService(),
            vectorStore = com.hermes.agent.data.memory.InMemoryVectorStore(),
            dispatchers = DefaultDispatcherProvider(),
        )

        val ctx = pipeline.buildContext("query", maxChars = 1000)
        assertEquals("", ctx)
    }
}

class DocumentChunkerTest {

    @Test
    fun `short text returns single chunk`() {
        val chunker = DocumentChunker(chunkSize = 100, chunkOverlap = 10)
        val chunks = chunker.split("hello world")
        assertEquals(1, chunks.size)
        assertEquals("hello world", chunks[0])
    }

    @Test
    fun `respects paragraph boundaries`() {
        val chunker = DocumentChunker(chunkSize = 30, chunkOverlap = 5)
        val text = "First paragraph here.\n\nSecond paragraph here.\n\nThird paragraph here."
        val chunks = chunker.split(text)
        assertTrue("should produce >1 chunk", chunks.size > 1)
    }

    @Test
    fun `falls back to sentence boundary when paragraphs too long`() {
        val chunker = DocumentChunker(chunkSize = 20, chunkOverlap = 5)
        val text = "This is a long sentence that exceeds the chunk size. Another long sentence follows it."
        val chunks = chunker.split(text)
        assertTrue("should produce >1 chunk", chunks.size > 1)
    }

    @Test
    fun `merges adjacent small pieces up to chunk size`() {
        val chunker = DocumentChunker(chunkSize = 50, chunkOverlap = 5)
        val text = "a\nb\nc\nd\ne"
        val chunks = chunker.split(text)
        // All small pieces should be merged into a single chunk.
        assertEquals(1, chunks.size)
    }
}
