package com.hermes.agent.data.repository

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.domain.agent.ExecutionOrigin
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val memoryRepository: com.hermes.agent.domain.repository.MemoryRepository,
    private val router: LlmRouter,
    private val orchestrator: Orchestrator,
    private val compressor: com.hermes.agent.data.llm.ConversationCompressor,
    private val dispatchers: DispatcherProvider,
) : ChatRepository {

    // Summarization must outlive the ViewModel that requests it: onCleared()
    // runs AFTER viewModelScope is cancelled, so launching there never
    // executes. This singleton's own supervisor scope actually survives.
    private val backgroundScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + dispatchers.io
    )

    // Message count at last summarization, per conversation — reopening and
    // closing an unchanged chat must not add a duplicate summary to memory.
    private val lastSummarizedCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    companion object {
        const val CONTEXT_WINDOW_MESSAGES = 20

        /** How far back to look for older turns to fold into the brief. */
        const val COMPRESSION_SCAN_MESSAGES = 120
        const val SYSTEM_PROMPT =
            "You are Hermes, a privacy-first AI agent for Android. " +
                "Be concise, helpful, and privacy-conscious. " +
                "When asked about device features, check what is available on this device."
    }

    override fun sendMessage(
        conversationId: String,
        content: String,
        attachmentUri: String?,
        attachmentMimeType: String?,
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
            attachmentUri = attachmentUri,
            attachmentMimeType = attachmentMimeType,
        )
        // The new-chat flow navigates to a client-generated id before any
        // conversation row exists — create it here so the message insert below
        // doesn't fail the message→conversation foreign key (SQLITE 787).
        conversationRepository.ensureConversation(conversationId)
        conversationRepository.addMessage(conversationId, userMessage)

        // 2. Build the LLM prompt: system + recent window + current user message.
        val recent = conversationRepository.getRecentMessages(
            conversationId,
            limit = CONTEXT_WINDOW_MESSAGES,
        )
        val llmMessages = buildList {
            add(LlmMessage(role = "system", content = SYSTEM_PROMPT))
            recent.forEach { m ->
                add(
                    LlmMessage(
                        role = m.role.wireName,
                        content = m.content,
                        attachmentUri = m.attachmentUri,
                        attachmentMimeType = m.attachmentMimeType,
                    ),
                )
            }
        }

        // 3. Route to a provider.
        val decision = router.route(llmMessages)
        val provider = when (decision) {
            is RoutingDecision.Ready -> decision.provider
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
        origin: ExecutionOrigin,
        attachmentUri: String?,
        attachmentMimeType: String?,
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
            attachmentUri = attachmentUri,
            attachmentMimeType = attachmentMimeType,
        )
        // The new-chat flow navigates to a client-generated id before any
        // conversation row exists — create it here so the message insert below
        // doesn't fail the message→conversation foreign key (SQLITE 787).
        conversationRepository.ensureConversation(conversationId)
        conversationRepository.addMessage(conversationId, userMessage)

        // 2. Build the window for the orchestrator: the newest
        //    CONTEXT_WINDOW_MESSAGES turns verbatim, and — when the thread is
        //    longer than that — a short brief of everything before them so the
        //    older context isn't just silently dropped.
        val scanned = conversationRepository.getRecentMessages(
            conversationId,
            limit = COMPRESSION_SCAN_MESSAGES,
        )
        val recent = scanned.takeLast(CONTEXT_WINDOW_MESSAGES)
        val older = scanned.dropLast(CONTEXT_WINDOW_MESSAGES)
        val brief = older.lastOrNull()?.let { anchor ->
            compressor.brief(
                conversationId,
                older.map { LlmMessage(role = it.role.wireName, content = it.content) },
                anchor.id,
            )
        }
        val llmMessages = buildList {
            // Orchestrator supplies its own system prompt per agent, so we
            // only include conversation turns (plus the earlier-context brief).
            brief?.let { add(LlmMessage(role = "system", content = "## Earlier in this conversation\n$it")) }
            recent.forEach { m ->
                add(
                    LlmMessage(
                        role = m.role.wireName,
                        content = m.content,
                        attachmentUri = m.attachmentUri,
                        attachmentMimeType = m.attachmentMimeType,
                    ),
                )
            }
        }

        // 3. Run the orchestrator and forward its events. Accumulate the
        //    final reply so we can persist a single assistant Message at
        //    the end.
        val accumulator = StringBuilder()
        var finalAgentRole: AgentRole = AgentRole.DEFAULT
        var finalIsOnDevice: Boolean = true
        var replyCompleted = false
        var failureMessage: String? = null

        try {
            orchestrator.run(conversationId, content, llmMessages, origin).collect { event ->
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
                        failureMessage = event.message
                    }
                    else -> { /* forward as-is */ }
                }
                emit(event)
            }
        } finally {
            // The turn is never left with no assistant message at all — whether
            // it Failed mid-stream, or the collector was cancelled (K21: the
            // user navigated away, the ViewModel was cleared). A cancelled
            // coroutine can still finish this write under NonCancellable.
            if (!replyCompleted) {
                withContext(NonCancellable) {
                    val text = accumulator.toString().ifBlank { failureMessage }
                        ?: "⏸ This reply was interrupted before it finished. Send your message again to retry."
                    if (text.isNotBlank()) {
                        conversationRepository.addMessage(
                            conversationId,
                            Message(
                                id = IdGenerator.newId(),
                                conversationId = conversationId,
                                role = MessageRole.ASSISTANT,
                                content = text,
                                agentRole = AgentRole.DEFAULT,
                                timestamp = System.currentTimeMillis(),
                                tokens = (text.length / 4).coerceAtLeast(1),
                                isOnDevice = finalIsOnDevice,
                            ),
                        )
                    }
                }
            }
        }
    }
        .catch { t ->
            Timber.tag("ChatRepo").w(t, "sendMessageOrchestrated failed")
            emit(OrchestratorEvent.Failed(t.message ?: "unknown error"))
        }
        .flowOn(dispatchers.io)
    override fun summarizeConversation(conversationId: String) {
        backgroundScope.launch {
            runCatching { doSummarize(conversationId) }
                .onFailure { Timber.tag("ChatRepo").w(it, "summarization failed") }
        }
    }

    private suspend fun doSummarize(conversationId: String) {
        val messages = conversationRepository.getRecentMessages(conversationId, limit = 50)
        if (messages.size < 4) return // Too short to summarize
        if (lastSummarizedCount[conversationId] == messages.size) return // Nothing new since last summary

        val conversationText = messages.joinToString("\n") { "${it.role.wireName}: ${it.content}" }

        val systemPrompt = """
            You are a summarization assistant. 
            Summarize the following conversation into a concise 1-2 sentence overview of what was discussed, 
            focusing on the user's intent, the context, and any key facts or outcomes. 
            Write the summary in the third person (e.g. "The user asked for...", "Hermes explained...").
            Do not include pleasantries.
        """.trimIndent()

        val llmMessages = listOf(
            LlmMessage(role = "system", content = systemPrompt),
            LlmMessage(role = "user", content = conversationText)
        )

        val decision = router.route(llmMessages)
        if (decision is RoutingDecision.Ready) {
            val response = runCatching { decision.provider.complete(llmMessages) }.getOrNull()
            if (response != null && response.content.isNotBlank()) {
                val summary = "Conversation Summary: ${response.content.trim()}"
                memoryRepository.addMemory(summary)
                lastSummarizedCount[conversationId] = messages.size
                Timber.tag("ChatRepo").i("Summarized conversation %s: %s", conversationId.take(8), summary.take(50))
            }
        }
    }
}
