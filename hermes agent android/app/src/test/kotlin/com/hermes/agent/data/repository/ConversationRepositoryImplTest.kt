package com.hermes.agent.data.repository

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.hermes.agent.data.local.HermesDatabase
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.util.DefaultDispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationRepositoryImplTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: HermesDatabase
    private lateinit var repo: ConversationRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HermesDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ConversationRepositoryImpl(
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            dispatchers = DefaultDispatcherProvider(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `createConversation persists and returns id`() = runTest {
        val id = repo.createConversation(title = "Test")
        val conv = repo.observeConversation(id).first()
        assertNotNull(conv)
        assertEquals("Test", conv?.title)
        assertEquals(0, conv?.messageCount)
    }

    @Test
    fun `addMessage persists message and updates conversation preview`() = runTest {
        val convId = repo.createConversation()
        val msg = Message(
            id = IdGenerator.newId(),
            conversationId = convId,
            role = MessageRole.USER,
            content = "Hello Hermes",
            agentRole = null,
            timestamp = System.currentTimeMillis(),
        )
        repo.addMessage(convId, msg)

        val messages = repo.observeMessages(convId).first()
        assertEquals(1, messages.size)
        assertEquals("Hello Hermes", messages[0].content)

        val conv = repo.observeConversation(convId).first()
        assertEquals(1, conv?.messageCount)
        assertEquals("Hello Hermes", conv?.lastMessagePreview)
    }

    @Test
    fun `observeConversations returns most-recently-updated first`() = runTest {
        val first = repo.createConversation(title = "First")
        val second = repo.createConversation(title = "Second")
        // Touch first by adding a message — its updated_at should now be newer.
        repo.addMessage(
            first,
            Message(
                id = IdGenerator.newId(),
                conversationId = first,
                role = MessageRole.USER,
                content = "ping",
                agentRole = null,
                timestamp = System.currentTimeMillis(),
            )
        )

        val list = repo.observeConversations().first()
        assertEquals(2, list.size)
        assertEquals("First", list[0].title)
    }

    @Test
    fun `deleteConversation cascades to messages`() = runTest {
        val convId = repo.createConversation()
        repo.addMessage(
            convId,
            Message(
                id = IdGenerator.newId(),
                conversationId = convId,
                role = MessageRole.USER,
                content = "to be deleted",
                agentRole = null,
                timestamp = System.currentTimeMillis(),
            )
        )
        repo.deleteConversation(convId)

        val conv = repo.observeConversation(convId).first()
        val messages = repo.observeMessages(convId).first()
        assertEquals(null, conv)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `getRecentMessages returns oldest-first`() = runTest {
        val convId = repo.createConversation()
        // Insert 5 messages with increasing timestamps.
        val baseTime = System.currentTimeMillis()
        repeat(5) { i ->
            repo.addMessage(
                convId,
                Message(
                    id = IdGenerator.newId(),
                    conversationId = convId,
                    role = MessageRole.USER,
                    content = "msg $i",
                    agentRole = if (i % 2 == 0) AgentRole.DEFAULT else null,
                    timestamp = baseTime + i * 1000L,
                )
            )
        }
        val recent = repo.getRecentMessages(convId, limit = 3)
        assertEquals(3, recent.size)
        // DAO returns newest-first; repo flips. So index 0 is the 3rd-newest.
        assertEquals("msg 2", recent[0].content)
        assertEquals("msg 4", recent[2].content)
    }

    @Test
    fun `renameConversation updates title`() = runTest {
        val convId = repo.createConversation(title = "Old")
        repo.renameConversation(convId, "New")
        val conv = repo.observeConversation(convId).first()
        assertEquals("New", conv?.title)
    }
}
