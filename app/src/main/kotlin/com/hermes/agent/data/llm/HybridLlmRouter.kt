package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class RoutingDecision {
    abstract val provider: LlmProvider

    data class OnDevice(override val provider: LlmProvider) : RoutingDecision()
    data class Cloud(override val provider: LlmProvider, val reason: String) : RoutingDecision()
    data class Unavailable(override val provider: LlmProvider, val reason: String) : RoutingDecision()
}

interface LlmRouter {
    suspend fun route(messages: List<LlmMessage>): RoutingDecision
}

@Singleton
class HybridLlmRouter @Inject constructor(
    private val cloud: CloudLlmProvider,
    private val settings: SettingsRepository,
) : LlmRouter {

    override suspend fun route(messages: List<LlmMessage>): RoutingDecision {
        val s = settings.current()
        if (!s.cloudEnabled || !cloud.isAvailable()) {
            val reason = if (!s.cloudEnabled) {
                "Cloud is disabled. Enable it and add an API key in Settings."
            } else {
                "Cloud is enabled but no API key is set. Add one in Settings."
            }
            return RoutingDecision.Unavailable(cloud, reason)
        }
        Timber.tag("LlmRouter").d("Route=cloud, model=${cloud.model}")
        return RoutingDecision.Cloud(cloud, "cloud provider")
    }
}
