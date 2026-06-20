package com.hermes.agent.data.repository

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.LlmStreamChunk
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.util.DispatcherProvider
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [ChatRepository] implementation.
 *
 * Phase 2 adds [sendMessageOrchestrated], which delegates to
 * [Orchestrator.run] and surfaces the full event stream (plan, tool
 * calls, per-step transitions) to the UI. The Phase 1 [sendMessage]
 * path is retained for backward compatibility and internal callers
 * (title generation, intent probes).
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val router: LlmRouter,
    private val orchestrator: Orchestrator,
    private val dispatchers: DispatcherProvider,
) : ChatRepository {

    companion object {
        const val CONTEXT_WINDOW_MESSAGES = 20
        const val SYSTEM_PROMPT =
            "You are Hermes, a privacy-first on-device AI agent for Android. " +
                "Be concise, helpful, and privacy-conscious. " +
                "When asked about device features, check what is available on this device."
    }

    override fun sendMessage(
        conversationId: String,
        content: String,
    ): Flow<ChatStreamEvent> = flow {
        // 1. Persist the user message.
        val now = System.currentTimeMillis()
        val userMessage = Message(
            id = IdGenerator.newId(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content,
            agentRole = null,
            timestamp = now,
            tokens = (content.length / 4).coerceAtLeast(1),
            isOnDevice = true,
        )
        conversationRepository.addMessage(conversationId, userMessage)

        // 2. Build the LLM prompt: system + recent window + current user message.
        val recent = conversationRepository.getRecentMessages(
            conversationId,
            limit = CONTEXT_WINDOW_MESSAGES,
        )
        val llmMessages = buildList {
            add(LlmMessage(role = "system", content = SYSTEM_PROMPT))
            recent.forEach { m ->
                add(LlmMessage(role = m.role.wireName, content = m.content))
            }
        }

        // 3. Route to a provider.
        val decision = router.route(llmMessages)
        val provider = when (decision) {
            is RoutingDecision.OnDevice -> decision.provider
            is RoutingDecision.Cloud -> decision.provider
            is RoutingDecision.Unavailable -> {
                emit(ChatStreamEvent.Error(IllegalStateException(decision.reason)))
                return@flow
            }
        }

        // 4. Stream tokens, accumulating into the final assistant message.
        val accumulator = StringBuilder()
        var consumedTokens = 0
        provider.stream(llmMessages).collect { chunk ->
            when (chunk) {
                is LlmStreamChunk.Delta -> {
                    accumulator.append(chunk.text)
                    emit(ChatStreamEvent.Token(chunk.text))
                }
                is LlmStreamChunk.Error -> {
                    emit(ChatStreamEvent.Error(RuntimeException(chunk.message)))
                    return@collect
                }
                LlmStreamChunk.Done -> {
                    consumedTokens = (accumulator.length / 4).coerceAtLeast(1)
                }
                is LlmStreamChunk.ToolCallDelta -> { /* no-op */ }
            }
        }

        // 5. Persist the assistant message.
        val assistantMessage = Message(
            id = IdGenerator.newId(),
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = accumulator.toString(),
            agentRole = AgentRole.DEFAULT,
            timestamp = System.currentTimeMillis(),
            tokens = consumedTokens,
            isOnDevice = provider.isOnDevice,
        )
        conversationRepository.addMessage(conversationId, assistantMessage)

        emit(ChatStreamEvent.Complete(assistantMessage))
    }
        .catch { t ->
            Timber.tag("ChatRepo").w(t, "sendMessage failed")
            emit(ChatStreamEvent.Error(t))
        }
        .flowOn(dispatchers.io)

    override fun sendMessageOrchestrated(
        conversationId: String,
        content: String,
    ): Flow<OrchestratorEvent> = flow {
        // 1. Persist the user message first so the orchestrator can see it
        //    in the recent window.
        val now = System.currentTimeMillis()
        val userMessage = Message(
            id = IdGenerator.newId(),
            conversationId = conversationId,
            role = MessageRole.USER,
            content = content,
            agentRole = null,
            timestamp = now,
            tokens = (content.length / 4).coerceAtLeast(1),
            isOnDevice = true,
        )
        conversationRepository.addMessage(conversationId, userMessage)

        // 2. Build the recent-window context for the orchestrator.
        val recent = conversationRepository.getRecentMessages(
            conversationId,
            limit = CONTEXT_WINDOW_MESSAGES,
        )
        val llmMessages = buildList {
            // Orchestrator supplies its own system prompt per agent, so we
            // only include conversation turns here.
            recent.forEach { m ->
                add(LlmMessage(role = m.role.wireName, content = m.content))
            }
        }

        // 3. Run the orchestrator and forward its events. Accumulate the
        //    final reply so we can persist a single assistant Message at
        //    the end.
        val accumulator = StringBuilder()
        var finalAgentRole: AgentRole = AgentRole.DEFAULT
        var finalIsOnDevice: Boolean = true
        var replyCompleted = false

        orchestrator.run(conversationId, content, llmMessages).collect { event ->
            when (event) {
                is OrchestratorEvent.ReplyToken -> accumulator.append(event.text)
                is OrchestratorEvent.ReplyComplete -> {
                    replyCompleted = true
                    finalAgentRole = event.agentRole
                    finalIsOnDevice = event.isOnDevice
                    val assistantMessage = Message(
                        id = IdGenerator.newId(),
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = event.finalText,
                        agentRole = event.agentRole,
                        timestamp = System.currentTimeMillis(),
                        tokens = (event.finalText.length / 4).coerceAtLeast(1),
                        isOnDevice = event.isOnDevice,
                    )
                    conversationRepository.addMessage(conversationId, assistantMessage)
                }
                is OrchestratorEvent.Failed -> {
                    Timber.tag("ChatRepo").w("orchestration failed: %s", event.message)
                }
                else -> { /* forward as-is */ }
            }
            emit(event)
        }

        // If the orchestrator stream ended without a ReplyComplete (e.g.
        // because of a Failed mid-stream), persist whatever partial text
        // we accumulated so the user doesn't lose it.
        if (accumulator.isNotBlank() && !replyCompleted) {
            val partialMessage = Message(
                id = IdGenerator.newId(),
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = accumulator.toString(),
                agentRole = AgentRole.DEFAULT,
                timestamp = System.currentTimeMillis(),
                tokens = (accumulator.length / 4).coerceAtLeast(1),
                isOnDevice = finalIsOnDevice,
            )
            conversationRepository.addMessage(conversationId, partialMessage)
        }
    }
        .catch { t ->
            Timber.tag("ChatRepo").w(t, "sendMessageOrchestrated failed")
            emit(OrchestratorEvent.Failed(t.message ?: "unknown error"))
        }
        .flowOn(dispatchers.io)
}
