package com.hermes.agent.plugin.tasker

import android.content.Intent
import android.os.Bundle
import com.hermes.agent.domain.model.AgentRole

object TaskerBundleHelper {

    const val ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"

    const val EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"

    const val KEY_AGENT_ROLE = "com.hermes.agent.extra.AGENT_ROLE"
    const val KEY_PROMPT_TEMPLATE = "com.hermes.agent.extra.PROMPT_TEMPLATE"
    const val KEY_TIMEOUT_SECONDS = "com.hermes.agent.extra.TIMEOUT_SECONDS"
    const val KEY_SPEAK_RESPONSE = "com.hermes.agent.extra.SPEAK_RESPONSE"

    /**
     * Capability token minted when the user approved the automation host, and
     * carried in the configuration the host persists. A broadcast has no sender
     * identity, so this is the only evidence at fire time that the configuration
     * came out of an approved handshake. See [TaskerHostAuthority].
     */
    const val KEY_HOST_TOKEN = "com.hermes.agent.extra.HOST_TOKEN"

    // Tasker variable output keys
    const val VAR_HERMES_RESULT = "%hermes_result"
    const val VAR_HERMES_EXIT_CODE = "%hermes_exit_code"

    data class TaskerConfig(
        val agentRole: AgentRole = AgentRole.CONVERSATIONAL,
        val promptTemplate: String = "",
        val timeoutSeconds: Int = 60,
        val speakResponse: Boolean = false,
        val hostToken: String = "",
    ) {
        fun toBundle(): Bundle = Bundle().apply {
            putString(KEY_AGENT_ROLE, agentRole.name)
            putString(KEY_PROMPT_TEMPLATE, promptTemplate)
            putInt(KEY_TIMEOUT_SECONDS, timeoutSeconds)
            putBoolean(KEY_SPEAK_RESPONSE, speakResponse)
            putString(KEY_HOST_TOKEN, hostToken)
        }

        fun toBlurb(): String {
            val roleName = agentRole.displayName
            val truncatedPrompt = if (promptTemplate.length > 40) {
                promptTemplate.take(37) + "..."
            } else {
                promptTemplate
            }
            return "$roleName: \"$truncatedPrompt\""
        }
    }

    fun fromBundle(bundle: Bundle?): TaskerConfig {
        if (bundle == null) return TaskerConfig()
        val roleStr = bundle.getString(KEY_AGENT_ROLE)
        val role = runCatching { AgentRole.valueOf(roleStr ?: "") }.getOrDefault(AgentRole.CONVERSATIONAL)
        val prompt = bundle.getString(KEY_PROMPT_TEMPLATE).orEmpty()
        val timeout = bundle.getInt(KEY_TIMEOUT_SECONDS, 60)
        val speak = bundle.getBoolean(KEY_SPEAK_RESPONSE, false)
        return TaskerConfig(
            agentRole = role,
            promptTemplate = prompt,
            timeoutSeconds = timeout,
            speakResponse = speak,
            hostToken = bundle.getString(KEY_HOST_TOKEN).orEmpty(),
        )
    }

    fun fromIntent(intent: Intent?): TaskerConfig {
        val bundle = intent?.getBundleExtra(EXTRA_BUNDLE)
        return fromBundle(bundle)
    }
}
