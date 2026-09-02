package com.hermes.agent.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridges intent-driven actions — share-to-Hermes, the notification-reply
 * action, and the voice quick-tile — from [com.hermes.agent.MainActivity]
 * into whichever [ChatScreen] ends up opened for them.
 *
 * A plain singleton rather than a Hilt-scoped one: [com.hermes.agent.MainActivity]
 * needs to publish an action on `onCreate`/`onNewIntent`, before the Compose
 * tree (and the Hilt-provided [ChatViewModel] for the new conversation) even
 * exists yet.
 */
object PendingChatIntent {

    sealed class Action {
        /** Send this text as the user's first message in the new chat. */
        data class SendText(val text: String) : Action()

        /** Arm voice listening as soon as the chat screen opens. */
        object ArmVoiceListen : Action()

        /**
         * Open hands-free Talk mode. Published when the wake word fires, so a
         * spoken trigger lands in a voice conversation rather than a text chat.
         */
        data class StartTalk(val targetAgent: String) : Action()
    }

    private val _pending = MutableStateFlow<Action?>(null)
    val pending: StateFlow<Action?> = _pending

    fun publish(action: Action) {
        _pending.value = action
    }

    /** Call once the action has been acted on, so it isn't replayed on rotation/recomposition. */
    fun consume() {
        _pending.value = null
    }
}
