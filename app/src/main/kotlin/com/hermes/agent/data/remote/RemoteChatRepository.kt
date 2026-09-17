package com.hermes.agent.data.remote

import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin-client [ChatRepository] that delegates to [RemoteOrchestrator].
 *
 * The key difference from [com.hermes.agent.data.repository.ChatRepositoryImpl]:
 * no local Room persistence. The PC gateway is the canonical store — it
 * persists the user message and assistant reply as part of the run. The
 * phone's conversation list comes from [RemoteConversationRepository],
 * which polls the PC's Sessions API.
 *
 *   - [sendMessageOrchestrated] delegates directly to the remote
 *     orchestrator and emits the same [OrchestratorEvent] stream the UI
 *     already renders.
 *   - [sendMessage] wraps the orchestrated flow and maps the rich events
 *     to the simpler [ChatStreamEvent] for backward compatibility.
 *   - [summarizeConversation] is a no-op: the PC handles memory and
 *     consolidation.
 */
@Singleton
class RemoteChatRepository @Inject constructor(
    private val remoteOrchestrator: RemoteOrchestrator,
    private val dispatchers: DispatcherProvider,
) : ChatRepository {

    override fun sendMessage(
        conversationId: String,
        content: String,
        attachmentUri: String?,
        attachmentMimeType: String?,
    ): Flow<ChatStreamEvent> = flow {
        // Emit the user message immediately so the UI shows it before the
        // remote run starts. The PC will persist it as part of the run.
        emit(ChatStreamEvent.Token(""))
        // Delegate to the orchestrated path and map to the simpler event type.
        var lastReply = ""
        remoteOrchestrator.run(
            conversationId = conversationId,
            userMessage = content,
            recentMessages = emptyList(),
            origin = ExecutionOrigin.INTERACTIVE,
        ).collect { event ->
            when (event) {
                is OrchestratorEvent.ReplyToken -> {
                    lastReply += event.text
                    emit(ChatStreamEvent.Token(event.text))
                }
                is OrchestratorEvent.ReplyComplete -> {
                    val message = Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = event.finalText,
                        agentRole = event.agentRole,
                        timestamp = System.currentTimeMillis(),
                        isOnDevice = false,
                    )
                    emit(ChatStreamEvent.Complete(message))
                }
                is OrchestratorEvent.Failed -> {
                    emit(ChatStreamEvent.Error(java.io.IOException(event.message)))
                }
                else -> { /* plan/step/tool events are not surfaced on the simple path */ }
            }
        }
    }.flowOn(dispatchers.io)

    override fun sendMessageOrchestrated(
        conversationId: String,
        content: String,
        origin: ExecutionOrigin,
        attachmentUri: String?,
        attachmentMimeType: String?,
    ): Flow<OrchestratorEvent> =
        remoteOrchestrator.run(
            conversationId = conversationId,
            userMessage = content,
            recentMessages = emptyList(),
            origin = origin,
        )

    /**
     * Thin-client only: ask the PC to stop the run currently streaming
     * to this phone, if any. Fire-and-forget; safe when nothing is running.
     */
    fun stopActiveRun() {
        remoteOrchestrator.stopActiveRun()
    }

    override fun summarizeConversation(conversationId: String) {
        // No-op: the PC gateway handles memory and consolidation.
        Timber.tag("RemoteChatRepo").d("summarizeConversation is a no-op in remote mode")
    }
}
