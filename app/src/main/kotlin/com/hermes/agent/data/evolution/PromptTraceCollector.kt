package com.hermes.agent.data.evolution

import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mines recent conversations for turns a specific agent role actually handled.
 *
 * The role filter comes from the `agent_role` stamped on each assistant
 * message, so this is evidence about *that* agent's behaviour rather than the
 * app's traffic in general — refining the productivity agent's prompt from
 * conversational small talk would teach it the wrong lesson.
 */
@Singleton
class PromptTraceCollector @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
) {

    data class Trace(val task: String, val response: String)

    suspend fun collectFor(
        role: AgentRole,
        maxTraces: Int = 8,
        maxConversations: Int = 40,
    ): List<Trace> = withContext(dispatchers.io) {
        val conversations = conversationDao.observeAll().first().take(maxConversations)
        val traces = mutableListOf<Trace>()

        for (conv in conversations) {
            val messages = messageDao.observeByConversation(conv.id).first() // ASC by timestamp
            for (i in messages.indices) {
                val msg = messages[i]
                if (msg.role != "assistant" || msg.agentRole != role.name) continue
                if (msg.content.isBlank() || TraceHeuristics.containsSecret(msg.content)) continue

                // Walk back to the user turn that prompted this reply, skipping
                // the tool messages that sit between them.
                var task = ""
                for (j in (i - 1) downTo 0) {
                    if (messages[j].role == "user") {
                        task = messages[j].content
                        break
                    }
                }
                if (task.length < 10 || TraceHeuristics.containsSecret(task)) continue

                traces += Trace(task = task.take(1200), response = msg.content.take(1200))
                if (traces.size >= maxTraces) return@withContext traces
            }
        }
        traces
    }
}
