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
import kotlinx.coroutines.asCoroutineDispatcher
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
        val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            HermesDatabase::class.java,
        ).allowMainThreadQueries()
         .build()
        repo = ConversationRepositoryImpl(
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            dispatchers = object : com.hermes.agent.util.DispatcherProvider {
                override val main = testDispatcher
                override val io = testDispatcher
                override val default = testDispatcher
                override val unconfined = testDispatcher
            },
        )
    }

    @After
    fun tearDown() {
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

    @Test
    fun `ensureConversation creates a row for a client-generated id`() = runTest {
        // Regression: the new-chat flow navigates to chat/{uuid} before any
        // conversation exists. Without ensureConversation, addMessage would fail
        // the message->conversation foreign key (SQLITE 787).
        val id = IdGenerator.newId()
        assertEquals(null, repo.observeConversation(id).first())

        repo.ensureConversation(id)

        val created = repo.observeConversation(id).first()
        assertNotNull(created)

        // The first message must now insert cleanly (no FK failure).
        repo.addMessage(
            id,
            Message(
                id = IdGenerator.newId(),
                conversationId = id,
                role = MessageRole.USER,
                content = "first message",
                agentRole = null,
                timestamp = System.currentTimeMillis(),
            ),
        )
        assertEquals(1, repo.observeMessages(id).first().size)
    }

    @Test
    fun `ensureConversation leaves an existing conversation untouched`() = runTest {
        val id = repo.createConversation(title = "Original")
        repo.ensureConversation(id, title = "Should Not Overwrite")
        assertEquals("Original", repo.observeConversation(id).first()?.title)
    }

    private suspend fun seedTurns(convId: String, base: Long): List<Message> {
        val msgs = listOf(
            Message(IdGenerator.newId(), convId, MessageRole.USER, "first question", null, base),
            Message(IdGenerator.newId(), convId, MessageRole.ASSISTANT, "first answer", null, base + 100),
            Message(IdGenerator.newId(), convId, MessageRole.USER, "second question", null, base + 200),
            Message(IdGenerator.newId(), convId, MessageRole.ASSISTANT, "second answer", null, base + 300),
        )
        msgs.forEach { repo.addMessage(convId, it) }
        return msgs
    }

    @Test
    fun `rewind drops the chosen message and everything after it`() = runTest {
        val convId = repo.createConversation()
        val msgs = seedTurns(convId, 1_000_000L)

        val removed = repo.rewindTo(convId, msgs[2])

        assertEquals(2, removed)
        val remaining = repo.observeMessages(convId).first()
        assertEquals(listOf("first question", "first answer"), remaining.map { it.content })
    }

    @Test
    fun `rewind rebuilds the conversation preview and count`() = runTest {
        val convId = repo.createConversation()
        val msgs = seedTurns(convId, 2_000_000L)

        repo.rewindTo(convId, msgs[2])

        // The preview and count are denormalised onto the conversation row, so a
        // rewind that left them alone would keep advertising a deleted message.
        val conv = repo.observeConversation(convId).first()
        assertEquals(2, conv?.messageCount)
        assertEquals("first answer", conv?.lastMessagePreview)
    }

    @Test
    fun `rewinding to the first message empties the conversation`() = runTest {
        val convId = repo.createConversation()
        val msgs = seedTurns(convId, 3_000_000L)

        val removed = repo.rewindTo(convId, msgs[0])

        assertEquals(4, removed)
        assertTrue(repo.observeMessages(convId).first().isEmpty())
        assertEquals(0, repo.observeConversation(convId).first()?.messageCount)
    }

    @Test
    fun `fork copies history up to the message and leaves the original intact`() = runTest {
        val convId = repo.createConversation()
        val msgs = seedTurns(convId, 4_000_000L)

        val forkId = repo.forkFrom(convId, msgs[1], "forked")

        val forked = repo.observeMessages(forkId).first()
        assertEquals(listOf("first question", "first answer"), forked.map { it.content })

        // Non-destructive: this is what makes fork the safe alternative to rewind.
        val original = repo.observeMessages(convId).first()
        assertEquals(4, original.size)
    }

    @Test
    fun `fork gives copied messages new ids so the two chats stay independent`() = runTest {
        val convId = repo.createConversation()
        val msgs = seedTurns(convId, 5_000_000L)

        val forkId = repo.forkFrom(convId, msgs[3], "forked")

        val originalIds = repo.observeMessages(convId).first().map { it.id }.toSet()
        val forkedIds = repo.observeMessages(forkId).first().map { it.id }.toSet()

        assertEquals(4, forkedIds.size)
        assertTrue("a fork must not share rows with its source", (originalIds intersect forkedIds).isEmpty())
        assertTrue(repo.observeMessages(forkId).first().all { it.conversationId == forkId })
    }
}
