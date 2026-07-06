package com.hermes.agent.data.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide "is the mic hot right now?" signal.
 *
 * [VoiceInputManager] flips this while its recognition Flow is active, so
 * any UI can reflect that Hermes is listening — the home screen's eyes
 * switch to the LISTENING mood (wide, attentive) the moment voice capture
 * begins anywhere in the app.
 */
object VoiceActivity {

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    fun setListening(value: Boolean) { _listening.value = value }
}
