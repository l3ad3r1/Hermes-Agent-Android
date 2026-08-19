package com.hermes.agent.domain.agent

import com.hermes.agent.domain.model.EvidenceState
import com.hermes.agent.domain.model.Memory
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UltraSkillInterceptorTest {

    private lateinit var interceptor: UltraSkillInterceptor
    private lateinit var fakeRepo: FakeConversationRepository
    private lateinit var fakeMemoryRepo: FakeMemoryRepository

    @Before
    fun setup() {
        fakeRepo = FakeConversationRepository()
        fakeMemoryRepo = FakeMemoryRepository()
        interceptor = UltraSkillInterceptor(fakeRepo, fakeMemoryRepo)
    }

    @Test
    fun `intercepts ulw-plan and adds PREPARED message`() = runBlocking {
        val result = interceptor.intercept("conv1", "ulw-plan do something")
        assertTrue(result)
        
        val messages = fakeRepo.messages
        assertEquals(2, messages.size)
        
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("ulw-plan do something", messages[0].content)
        
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(EvidenceState.PREPARED, messages[1].evidenceState)
    }

    @Test
    fun `intercepts slash plan and adds PREPARED message`() = runBlocking {
        val result = interceptor.intercept("conv1", "/plan build a new UI")
        assertTrue(result)
        
        val messages = fakeRepo.messages
        assertEquals(2, messages.size)
        
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("/plan build a new UI", messages[0].content)
        
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(EvidenceState.PREPARED, messages[1].evidenceState)
    }

    @Test
    fun `intercepts ulw-research and adds RUNNING message`() = runBlocking {
        val result = interceptor.intercept("conv1", "ulw-research topic")
        assertTrue(result)
        
        val messages = fakeRepo.messages
        assertEquals(2, messages.size)
        
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("ulw-research topic", messages[0].content)
        
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(EvidenceState.RUNNING, messages[1].evidenceState)
    }

    @Test
    fun `intercepts slash memory and persists to repository`() = runBlocking {
        val result = interceptor.intercept("conv1", "/memory user loves dark theme")
        assertTrue(result)
        
        val messages = fakeRepo.messages
        assertEquals(2, messages.size)
        assertEquals("user loves dark theme", fakeMemoryRepo.addedMemories.firstOrNull())
        assertTrue(messages[1].content.contains("Stored to Starmap memory"))
    }

    @Test
    fun `intercepts slash clear and adds reset notice`() = runBlocking {
        val result = interceptor.intercept("conv1", "/clear")
        assertTrue(result)
        
        val messages = fakeRepo.messages
        assertEquals(2, messages.size)
        assertTrue(messages[1].content.contains("Chat context cleared"))
    }

    @Test
    fun `ignores normal messages`() = runBlocking {
        val result = interceptor.intercept("conv1", "hello world")
        assertFalse(result)
        
        assertEquals(0, fakeRepo.messages.size)
    }
}

class FakeConversationRepository : ConversationRepository {
    val messages = mutableListOf<Message>()
    
    override suspend fun addMessage(conversationId: String, message: Message): String {
        messages.add(message)
        return message.id
    }
    
    // Stub implementations for the rest
    override fun observeConversations(): Flow<List<com.hermes.agent.domain.model.Conversation>> = TODO()
    override fun observeConversation(id: String): Flow<com.hermes.agent.domain.model.Conversation?> = TODO()
    override fun observeMessages(conversationId: String): Flow<List<Message>> = TODO()
    override suspend fun createConversation(title: String): String = TODO()
    override suspend fun ensureConversation(id: String, title: String) = TODO()
    override suspend fun renameConversation(id: String, title: String) = TODO()
    override suspend fun deleteConversation(id: String) = TODO()
    override suspend fun getRecentMessages(conversationId: String, limit: Int): List<Message> = TODO()
}

class FakeMemoryRepository : MemoryRepository {
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
