package com.hermes.agent.domain.model

/**
 * Streaming events emitted by [com.hermes.agent.domain.repository.ChatRepository.sendMessage].
 *
 * The chat screen consumes these as a Flow and appends tokens to the current
 * assistant message bubble as they arrive, then commits the final message
 * on [Complete]. Errors are surfaced inline rather than thrown so the UI
 * can keep the partial response visible.
 */
sealed class ChatStreamEvent {

    /** A partial token (word fragment or whole word) of the assistant reply. */
    data class Token(val text: String) : ChatStreamEvent()

    /**
     * The assistant message has finished streaming. Carries the fully
     * assembled [Message] that has already been persisted to Room.
     */
    data class Complete(val message: Message) : ChatStreamEvent()

    /**
     * A non-fatal error mid-stream. The stream terminates after this event.
     * Any tokens already emitted remain visible to the user.
     */
    data class Error(val throwable: Throwable) : ChatStreamEvent()
}
