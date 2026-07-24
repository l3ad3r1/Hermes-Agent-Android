package com.hermes.agent.service

import com.hermes.agent.data.tools.WebhookTool
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.KanbanStatus
import com.hermes.agent.domain.model.KanbanTicket
import com.hermes.agent.domain.repository.KanbanRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber

@Singleton
class KanbanTaskProcessor @Inject constructor(
    private val kanbanRepository: KanbanRepository,
    private val orchestrator: Orchestrator,
    private val webhookTool: WebhookTool,
) {
    private val processMutex = Mutex()

    suspend fun processNext(
        onStarted: (KanbanTicket) -> Unit = {},
        onCompleted: (KanbanTicket) -> Unit = {},
    ): Boolean = processMutex.withLock {
        val ticket = kanbanRepository.nextTodo() ?: return false
        onStarted(ticket)
        kanbanRepository.moveTo(ticket.id, KanbanStatus.IN_PROGRESS)

        val prompt = buildString {
            append(ticket.title)
            if (ticket.body.isNotBlank()) append("\n\n${ticket.body}")
        }
        val result = runCatching {
            val events = orchestrator.run(
                conversationId = "kanban-${ticket.id}",
                userMessage = prompt,
                recentMessages = emptyList(),
                origin = ExecutionOrigin.BACKGROUND,
            ).toList()
            events.filterIsInstance<OrchestratorEvent.ReplyComplete>()
                .firstOrNull()?.finalText
                ?: events.filterIsInstance<OrchestratorEvent.ReplyToken>()
                    .joinToString("") { it.text }
                    .ifBlank { "Completed (no reply)." }
        }.getOrElse { error ->
            Timber.e(error, "Orchestrator failed for ticket ${ticket.id}")
            "Agent error: ${error.message}"
        }

        kanbanRepository.complete(ticket.id, result)
        onCompleted(ticket)
        runCatching {
            val args: Map<String, JsonElement> = mapOf(
                "message" to JsonPrimitive("Completed ticket ${ticket.id}: ${ticket.title}"),
            )
            webhookTool.execute(args)
        }.onFailure { Timber.w(it, "notify after ticket completion failed") }
        true
    }
}
