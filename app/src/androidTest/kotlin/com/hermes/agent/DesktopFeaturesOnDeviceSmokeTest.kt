package com.hermes.agent

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
import com.hermes.agent.data.repository.SessionRepository
import com.hermes.agent.data.settings.SettingsRepositoryImpl
import com.hermes.agent.di.DatabaseModule
import com.hermes.agent.ui.chat.components.ArtifactExtractor
import com.hermes.agent.ui.chat.components.HERMES_SLASH_COMMANDS
import com.hermes.agent.ui.experiment.ModelBenchmarkMetrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumentation smoke test for all ported Hermes Desktop capabilities.
 * Runs on connected Samsung Galaxy S24 Ultra hardware.
 */
@RunWith(AndroidJUnit4::class)
class DesktopFeaturesOnDeviceSmokeTest {

    private lateinit var database: HermesDatabase
    private lateinit var settingsRepository: SettingsRepositoryImpl
    private lateinit var memoryDao: MemoryDao
    private lateinit var kanbanDao: KanbanTicketDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao
    private lateinit var sessionRepository: SessionRepository

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
    }

    @Test
    fun testDesktopPortedFeaturesOnDevice() {
        runBlocking {
            // 1. Verify Slash Commands registry
            val planCmd = HERMES_SLASH_COMMANDS.find { it.command == "/plan" }
            val kanbanCmd = HERMES_SLASH_COMMANDS.find { it.command == "/kanban" }
            val ultrabrainCmd = HERMES_SLASH_COMMANDS.find { it.command == "/model ultrabrain" }
            val memCmd = HERMES_SLASH_COMMANDS.find { it.command == "/memory" }

            assertNotNull("Plan command missing", planCmd)
            assertNotNull("Kanban command missing", kanbanCmd)
            assertNotNull("Ultrabrain command missing", ultrabrainCmd)
            assertNotNull("Memory command missing", memCmd)

            // 2. Verify Artifact Extraction on Android Runtime
            val testCode = """
                ```html
                <div class="card"><h1>Hermes S24 Ultra</h1></div>
                ```
                ```kotlin
                fun main() = println("Hello from Galaxy S24")
                ```
            """.trimIndent()
            val artifacts = ArtifactExtractor.extractArtifacts(testCode)
            assertEquals(2, artifacts.size)
            assertEquals("html", artifacts[0].language)
            assertEquals("Web Page Preview", artifacts[0].title)
            assertEquals("kotlin", artifacts[1].language)
            assertEquals("Kotlin Source", artifacts[1].title)

            // 3. Verify Telegram 24/7 Gateway Bot Settings Persistence in DataStore
            settingsRepository.setTelegramBotEnabled(true)
            settingsRepository.setTelegramBotToken("123456:TEST_TELEGRAM_TOKEN")
            settingsRepository.setTelegramAllowedUserIds("99887766, 11223344")

            val currentSettings = settingsRepository.current()
            assertTrue("Telegram bot should be enabled", currentSettings.telegramBotEnabled)
            assertEquals("123456:TEST_TELEGRAM_TOKEN", currentSettings.telegramBotToken)
            assertEquals("99887766, 11223344", currentSettings.telegramAllowedUserIds)

            // Reset test settings
            settingsRepository.setTelegramBotEnabled(false)
            settingsRepository.setTelegramBotToken("")
            settingsRepository.setTelegramAllowedUserIds("")

            // 4. Verify Memory persistence for Starmap Knowledge Graph
            val now = System.currentTimeMillis()
            val testMemory = MemoryEntity(
                id = "mem_test_s24",
                content = "Test Starmap Knowledge Node on Samsung S24",
                createdAt = now,
                lastAccessedAt = now,
            )
            memoryDao.upsert(testMemory)
            val memories = memoryDao.observeAll().first()
            val created = memories.firstOrNull { it.id == "mem_test_s24" }
            assertNotNull("Memory node should exist in Room DB", created)
            memoryDao.delete("mem_test_s24")

            // 5. Verify Autonomous Kanban Ticket Persistence on Device
            val testTicket = KanbanTicketEntity(
                id = "kbn_test_s24",
                title = "Verify S24 Ultra Hardware Execution",
                body = "Automated on-device instrumentation smoke test",
                status = "TODO",
                assignee = "agent",
                createdBy = "user",
                priority = "HIGH",
                tagsJson = "[\"s24\",\"test\"]",
                result = null,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
            )
            kanbanDao.upsert(testTicket)
            val tickets = kanbanDao.observeAll().first()
            val foundTicket = tickets.firstOrNull { it.id == "kbn_test_s24" }
            assertNotNull("Kanban ticket should exist on device", foundTicket)
            assertEquals("HIGH", foundTicket?.priority)
            kanbanDao.delete("kbn_test_s24")

            // 6. Verify Session Markdown Export on Device
            val testConvId = "conv_export_s24"
            conversationDao.upsert(
                ConversationEntity(
                    id = testConvId,
                    title = "Galaxy S24 Test Session",
                    createdAt = now,
                    updatedAt = now,
                    lastMessagePreview = "Hello S24",
                    messageCount = 2,
                )
            )
            messageDao.upsert(
                MessageEntity(
                    id = "msg_999901",
                    conversationId = testConvId,
                    role = "user",
                    content = "Plan the project",
                    agentRole = null,
                    timestamp = now,
                )
            )
            messageDao.upsert(
                MessageEntity(
                    id = "msg_999902",
                    conversationId = testConvId,
                    role = "assistant",
                    content = "Project planned successfully!",
                    agentRole = "PRIMARY",
                    timestamp = now + 1000,
                )
            )

            val markdownExport = sessionRepository.exportToMarkdown(testConvId)
            assertTrue("Markdown export should have title", markdownExport.contains("Galaxy S24 Test Session"))
            assertTrue("Markdown export should have user message", markdownExport.contains("Plan the project"))
            assertTrue("Markdown export should have assistant response", markdownExport.contains("Project planned successfully!"))

            // Cleanup session
            conversationDao.delete(testConvId)

            // 7. Verify Model Benchmark Telemetry Model
            val benchmarkMetrics = ModelBenchmarkMetrics(
                ttftMs = 185L,
                totalTimeMs = 1200L,
                tokenCount = 54,
                tokensPerSec = 45.0,
            )
            assertEquals(185L, benchmarkMetrics.ttftMs)
            assertEquals(45.0, benchmarkMetrics.tokensPerSec, 0.01)
        }
    }
}
