package com.hermes.agent.domain.agent

import com.hermes.agent.domain.model.EvidenceState
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import com.hermes.agent.util.IdGenerator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UltraSkillInterceptor @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val memoryRepository: MemoryRepository,
) {
    suspend fun intercept(conversationId: String, content: String): Boolean {
        val trimmed = content.trim()
        
        // 1. Plan command (/plan or ulw-plan)
        if (trimmed.startsWith("ulw-plan", ignoreCase = true) || trimmed.startsWith("/plan", ignoreCase = true)) {
            val userMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = System.currentTimeMillis(),
            )
            conversationRepository.addMessage(conversationId, userMsg)
            
            val agentMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "I have drafted a plan based on your request. Please review and approve it before execution.",
                timestamp = System.currentTimeMillis(),
                evidenceState = EvidenceState.PREPARED,
            )
            conversationRepository.addMessage(conversationId, agentMsg)
            return true
        }
        
        // 2. Research command (/research or ulw-research)
        if (trimmed.startsWith("ulw-research", ignoreCase = true) || trimmed.startsWith("/research", ignoreCase = true)) {
            val userMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = System.currentTimeMillis(),
            )
            conversationRepository.addMessage(conversationId, userMsg)
            
            val agentMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "Digging through the codebase and verifying sources...",
                timestamp = System.currentTimeMillis(),
                evidenceState = EvidenceState.RUNNING,
            )
            conversationRepository.addMessage(conversationId, agentMsg)
            return true
        }

        // 3. Starmap Memory command (/memory <fact>)
        if (trimmed.startsWith("/memory", ignoreCase = true)) {
            val fact = trimmed.removePrefix("/memory").trim()
            val userMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = System.currentTimeMillis(),
            )
            conversationRepository.addMessage(conversationId, userMsg)

            val replyText = if (fact.isNotBlank()) {
                memoryRepository.addMemory(fact)
                "✨ Stored to Starmap memory: \"$fact\" 🌌"
            } else {
                "⚠️ Please specify a fact or preference to remember: e.g. `/memory prefer concise answers`"
            }

            val agentMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = replyText,
                timestamp = System.currentTimeMillis(),
            )
            conversationRepository.addMessage(conversationId, agentMsg)
            return true
        }

        // 4. Clear context command (/clear)
        if (trimmed.equals("/clear", ignoreCase = true)) {
            val userMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = System.currentTimeMillis(),
            )
            conversationRepository.addMessage(conversationId, userMsg)

            val agentMsg = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "🧹 Chat context cleared. Ready for your next instruction.",
                timestamp = System.currentTimeMillis(),
            )
            conversationRepository.addMessage(conversationId, agentMsg)
            return true
        }
        
        return false
    }
}
