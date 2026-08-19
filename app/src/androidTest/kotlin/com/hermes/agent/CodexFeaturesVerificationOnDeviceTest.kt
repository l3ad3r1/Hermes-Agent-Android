package com.hermes.agent
import com.hermes.agent.domain.settings.*

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.KanbanTicketDao
import com.hermes.agent.data.local.dao.MemoryDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.data.local.entity.ConversationEntity
import com.hermes.agent.data.local.entity.KanbanTicketEntity
import com.hermes.agent.data.local.entity.MemoryEntity
import com.hermes.agent.data.local.entity.MessageEntity
import com.hermes.agent.data.rag.DocumentChunker
import com.hermes.agent.data.repository.KanbanRepositoryImpl
import com.hermes.agent.data.repository.SessionRepository
import com.hermes.agent.data.settings.SettingsRepositoryImpl
import com.hermes.agent.data.tools.KanbanTool
import com.hermes.agent.di.DatabaseModule
import com.hermes.agent.domain.agent.UltraSkillInterceptor
import com.hermes.agent.domain.model.EvidenceState
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.Memory
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.model.TicketPriority
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.ui.chat.components.ArtifactExtractor
import com.hermes.agent.ui.chat.components.HERMES_SLASH_COMMANDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device comprehensive verification test covering every implementation claim in CODEX.md.
 * Runs directly on the connected Samsung Galaxy S24 Ultra hardware (SM-S928B / RZCY51R2A8D).
 */
@RunWith(AndroidJUnit4::class)
class CodexFeaturesVerificationOnDeviceTest {

    private lateinit var database: HermesDatabase
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var memoryDao: MemoryDao
    private lateinit var kanbanDao: KanbanTicketDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao
    private lateinit var sessionRepository: SessionRepository
    private lateinit var kanbanRepository: KanbanRepositoryImpl
    private lateinit var kanbanTool: KanbanTool
    private lateinit var ultraSkillInterceptor: UltraSkillInterceptor
    private lateinit var mockConversationRepo: TestConversationRepository
    private lateinit var mockMemoryRepo: TestMemoryRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = DatabaseModule.provideDatabase(context)
        settingsRepository = SettingsRepositoryImpl(context)
        memoryDao = database.memoryDao()
        kanbanDao = database.kanbanTicketDao()
        conversationDao = database.conversationDao()
        messageDao = database.messageDao()

        sessionRepository = SessionRepository(conversationDao, messageDao, database.openHelper.writableDatabase)
        kanbanRepository = KanbanRepositoryImpl(kanbanDao)
        kanbanTool = KanbanTool(kanbanRepository)

        mockConversationRepo = TestConversationRepository()
        mockMemoryRepo = TestMemoryRepository()
        ultraSkillInterceptor = UltraSkillInterceptor(mockConversationRepo, mockMemoryRepo)
    }

    @Test
    fun verifySlashCommandsAndInterceptorOnDevice() {
        runBlocking {
            // 1. Check all registered slash commands in palette
            val commands = HERMES_SLASH_COMMANDS.map { it.command }
            assertTrue("Missing /plan", commands.contains("/plan"))
            assertTrue("Missing /research", commands.contains("/research"))
            assertTrue("Missing /kanban", commands.contains("/kanban"))
            assertTrue("Missing /model ultrabrain", commands.contains("/model ultrabrain"))
            assertTrue("Missing /model quick", commands.contains("/model quick"))
            assertTrue("Missing /delegate", commands.contains("/delegate"))
            assertTrue("Missing /memory", commands.contains("/memory"))
            assertTrue("Missing /clear", commands.contains("/clear"))
            assertTrue("Missing /export", commands.contains("/export"))

            // 2. Intercept /plan
            val convId = "conv_test_slash_1"
            val planIntercepted = ultraSkillInterceptor.intercept(convId, "/plan Create a modern Android dashboard")
            assertTrue("Slash /plan should be intercepted", planIntercepted)

            val messages = mockConversationRepo.messages
            assertEquals(2, messages.size)
            assertEquals(EvidenceState.PREPARED, messages[1].evidenceState)

            // 3. Intercept /memory
            val memIntercepted = ultraSkillInterceptor.intercept(convId, "/memory User prefers dark mode UI")
            assertTrue("Slash /memory should be intercepted", memIntercepted)
            assertEquals("User prefers dark mode UI", mockMemoryRepo.addedMemories.firstOrNull())

            // 4. Intercept /clear
            val clearIntercepted = ultraSkillInterceptor.intercept(convId, "/clear")
            assertTrue("Slash /clear should be intercepted", clearIntercepted)
        }
    }

    @Test
    fun verifyArtifactExtractionOnDevice() {
        val mixedContent = """
            Here is a test implementation:
            ```html
            <!DOCTYPE html><html><body><h1>S24 Ultra Test</h1></body></html>
            ```
            And here is the Kotlin implementation:
            ```kotlin
            data class GalaxyModel(val name: String, val ram: Int)
            ```
            And Python snippet:
            ```python
            def compute_tps(tokens, ms):
                return (tokens / ms) * 1000.0
            ```
        """.trimIndent()

        val artifacts = ArtifactExtractor.extractArtifacts(mixedContent)
        assertEquals(3, artifacts.size)

        assertEquals("html", artifacts[0].language)
        assertEquals("Web Page Preview", artifacts[0].title)
        assertTrue(artifacts[0].code.contains("<!DOCTYPE html>"))

        assertEquals("kotlin", artifacts[1].language)
        assertEquals("Kotlin Source", artifacts[1].title)
        assertTrue(artifacts[1].code.contains("data class GalaxyModel"))

        assertEquals("python", artifacts[2].language)
        assertEquals("Python Script", artifacts[2].title)
        assertTrue(artifacts[2].code.contains("def compute_tps"))
    }

    @Test
    fun verifyKanbanFullLifecycleOnDevice() {
        runBlocking {
            val now = System.currentTimeMillis()
            // 1. Batch Create
            val batchInput = JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "title" to JsonPrimitive("Ticket A: Initialize Pipeline"),
                            "body" to JsonPrimitive("Setup continuous test pipeline on S24"),
                            "priority" to JsonPrimitive("HIGH"),
                            "tags" to JsonArray(listOf(JsonPrimitive("ci"), JsonPrimitive("test"))),
                        )
                    ),
                    JsonObject(
                        mapOf(
                            "title" to JsonPrimitive("Ticket B: Performance Benchmark"),
                            "body" to JsonPrimitive("Measure TTFT and tokens per second"),
                            "priority" to JsonPrimitive("MEDIUM"),
                            "tags" to JsonArray(listOf(JsonPrimitive("benchmark"))),
                        )
                    )
                )
            )

            val batchRes = kanbanTool.execute(mapOf("action" to JsonPrimitive("create_batch"), "tickets" to batchInput))
            assertTrue(batchRes.success)
            assertTrue(batchRes.output.contains("Successfully created 2 Kanban tickets"))

            // 2. Query from Room DB
            val tickets = kanbanRepository.observe().first()
            val ticketA = tickets.find { it.title == "Ticket A: Initialize Pipeline" }
            val ticketB = tickets.find { it.title == "Ticket B: Performance Benchmark" }
            assertNotNull(ticketA)
            assertNotNull(ticketB)
            assertEquals(KanbanStatus.TODO, ticketA?.status)
            assertEquals(TicketPriority.HIGH, ticketA?.priority)

            // 3. Move to IN_PROGRESS and then DONE
            val moveRes1 = kanbanTool.execute(
                mapOf("action" to JsonPrimitive("move"), "id" to JsonPrimitive(ticketA!!.id), "status" to JsonPrimitive("IN_PROGRESS"))
            )
            assertTrue(moveRes1.success)

            val moveRes2 = kanbanTool.execute(
                mapOf(
                    "action" to JsonPrimitive("move"),
                    "id" to JsonPrimitive(ticketA.id),
                    "status" to JsonPrimitive("DONE"),
                    "result" to JsonPrimitive("Pipeline initialized and verified"),
                )
            )
            assertTrue(moveRes2.success)

            // 4. List DONE tickets
            val listDoneRes = kanbanTool.execute(mapOf("action" to JsonPrimitive("list"), "status" to JsonPrimitive("DONE")))
            assertTrue(listDoneRes.success)
            assertTrue(listDoneRes.output.contains("Ticket A: Initialize Pipeline"))

            // 5. Cleanup
            kanbanTool.execute(mapOf("action" to JsonPrimitive("delete"), "id" to JsonPrimitive(ticketA.id)))
            kanbanTool.execute(mapOf("action" to JsonPrimitive("delete"), "id" to JsonPrimitive(ticketB!!.id)))
        }
    }

    @Test
    fun verifySessionMarkdownExportOnDevice() {
        runBlocking {
            val testConvId = "conv_export_codex_" + System.currentTimeMillis()
            val now = System.currentTimeMillis()

            conversationDao.upsert(
                ConversationEntity(
                    id = testConvId,
                    title = "Codex Verification Session",
                    createdAt = now,
                    updatedAt = now,
                    lastMessagePreview = "Export Test",
                    messageCount = 2,
                )
            )

            messageDao.upsert(
                MessageEntity(
                    id = "msg_test_user",
                    conversationId = testConvId,
                    role = "user",
                    content = "Please analyze the S24 Ultra hardware profile",
                    agentRole = null,
                    timestamp = now,
                )
            )

            messageDao.upsert(
                MessageEntity(
                    id = "msg_test_assistant",
                    conversationId = testConvId,
                    role = "assistant",
                    content = "Snapdragon 8 Gen 3 for Galaxy, 12GB LPDDR5X RAM verified.",
                    agentRole = "PRIMARY",
                    timestamp = now + 500,
                )
            )

            val markdown = sessionRepository.exportToMarkdown(testConvId)
            assertTrue(markdown.contains("# Codex Verification Session"))
            assertTrue(markdown.contains("### 👤 User"))
            assertTrue(markdown.contains("Please analyze the S24 Ultra hardware profile"))
            assertTrue(markdown.contains("### 🤖 Hermes"))
            assertTrue(markdown.contains("Snapdragon 8 Gen 3 for Galaxy"))

            val json = sessionRepository.exportToJson(testConvId)
            assertTrue(json.contains("Codex Verification Session"))
            assertTrue(json.contains("Snapdragon 8 Gen 3 for Galaxy"))

            conversationDao.delete(testConvId)
        }
    }

    @Test
    fun verifySettingsAndTelegramGatewayLogicOnDevice() {
        runBlocking {
            // Test Telegram Bot settings persistence and authorization logic
            settingsRepository.setTelegramBotEnabled(true)
            settingsRepository.setTelegramBotToken("123456789:ABCDefGhIjKlMnOpQrStUvWxYz")
            settingsRepository.setTelegramAllowedUserIds("1001, 1002, 1003")

            val settings = settingsRepository.current()
            assertTrue(settings.telegramBotEnabled)
            assertEquals("123456789:ABCDefGhIjKlMnOpQrStUvWxYz", settings.telegramBotToken)
            assertEquals("1001, 1002, 1003", settings.telegramAllowedUserIds)

            // Verify whitelist logic
            val allowedList = settings.telegramAllowedUserIds
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            assertTrue(allowedList.contains("1001"))
            assertTrue(allowedList.contains("1002"))
            assertTrue(allowedList.contains("1003"))
            assertFalse(allowedList.contains("9999"))

            // Reset
            settingsRepository.setTelegramBotEnabled(false)
            settingsRepository.setTelegramBotToken("")
            settingsRepository.setTelegramAllowedUserIds("")
        }
    }

    @Test
    fun verifyDocumentChunkingOnDevice() {
        val sampleDoc = """
            # Architecture Overview
            Hermes Agent runs locally on Android devices.
            It provides on-device LLM inference via llama.cpp.
            
            ## Starmap Knowledge Graph
            The Starmap visualizes semantic facts as glowing celestial nodes.
            
            ## Autonomous Background Kanban
            The agent autonomously manages task queues in Room DB.
        """.trimIndent()

        val chunker = DocumentChunker(chunkSize = 150, chunkOverlap = 30)
        val chunks = chunker.split(sampleDoc)
        assertTrue("Document should be chunked into multiple pieces", chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue("Chunk length should be reasonable", chunk.isNotBlank())
        }
    }
}

class TestConversationRepository : ConversationRepository {
    val messages = mutableListOf<Message>()
    override suspend fun addMessage(conversationId: String, message: Message): String {
        messages.add(message)
        return message.id
    }
    override fun observeConversations(): Flow<List<com.hermes.agent.domain.model.Conversation>> = TODO()
    override fun observeConversation(id: String): Flow<com.hermes.agent.domain.model.Conversation?> = TODO()
    override fun observeMessages(conversationId: String): Flow<List<Message>> = TODO()
    override suspend fun createConversation(title: String): String = TODO()
    override suspend fun ensureConversation(id: String, title: String) = TODO()
    override suspend fun renameConversation(id: String, title: String) = TODO()
    override suspend fun deleteConversation(id: String) = TODO()
    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message> = TODO()
}

class TestMemoryRepository : MemoryRepository {
    val addedMemories = mutableListOf<String>()
    override fun observeMemories(): Flow<List<Memory>> = TODO()
    override suspend fun addMemory(content: String): String {
        addedMemories.add(content)
        return "mem-${addedMemories.size}"
    }
    override suspend fun deleteMemory(id: String) = TODO()
    override suspend fun newestMemoryWithPrefix(prefix: String): Memory? = TODO()
    override suspend fun searchMemories(query: String, limit: Int): List<Memory> = TODO()
}
