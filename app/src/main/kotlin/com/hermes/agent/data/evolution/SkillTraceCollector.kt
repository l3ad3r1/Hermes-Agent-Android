package com.hermes.agent.data.evolution

import com.hermes.agent.data.local.dao.ConversationDao
import com.hermes.agent.data.local.dao.MessageDao
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mines recent on-device conversations for traces relevant to a skill: a real
 * user task paired with the assistant's response. This is the on-device analog
 * of the offline tool's SessionDB mining — it gives the reflective refiner
 * concrete evidence of how a skill actually performed.
 */
@Singleton
class SkillTraceCollector @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dispatchers: DispatcherProvider,
) {

    data class Trace(val task: String, val response: String)

    /**
     * @param maxTraces        cap on traces returned (kept small — they go into a prompt).
     * @param maxConversations how many recent conversations to scan.
     */
    suspend fun collectFor(
        skillName: String,
        skillText: String,
        maxTraces: Int = 8,
        maxConversations: Int = 40,
    ): List<Trace> = withContext(dispatchers.io) {
        val conversations = conversationDao.observeAll().first().take(maxConversations)
        val traces = mutableListOf<Trace>()

        for (conv in conversations) {
            val messages = messageDao.observeByConversation(conv.id).first() // ASC by timestamp
            for (i in messages.indices) {
                val msg = messages[i]
                if (msg.role != "user") continue
                val task = msg.content
                if (task.length < 10 || TraceHeuristics.containsSecret(task)) continue
                if (!TraceHeuristics.isRelevantToSkill(task, skillName, skillText)) continue

                // Pair with the next assistant reply (skip interleaved tool msgs).
                var response = ""
                for (j in (i + 1) until messages.size) {
                    when (messages[j].role) {
                        "assistant" -> { response = messages[j].content; }
                        "user" -> {} // fall through to break below
                        else -> continue
                    }
                    break
                }
                if (response.isNotBlank() && TraceHeuristics.containsSecret(response)) response = ""

                traces += Trace(task = task.take(1200), response = response.take(1200))
                if (traces.size >= maxTraces) return@withContext traces
            }
        }
        traces
    }
}
