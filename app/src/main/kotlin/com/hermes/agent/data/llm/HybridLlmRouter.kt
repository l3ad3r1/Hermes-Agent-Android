package com.hermes.agent.data.llm

import com.hermes.agent.data.settings.SettingsRepository
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
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
    @Named("cloudAux") private val specialised: CloudLlmProvider,
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

        // Two cloud models, one per task class: complex requests go to the
        // primary model (settings.cloudModel); simpler ones to the lighter
        // specialised model (settings.auxModel). Both share the same API key
        // and base URL, so the specialised model acts as a backup too — if it
        // is somehow unavailable we fall back to the primary.
        val lastUserMessage = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        return when (ComplexityClassifier.classify(lastUserMessage)) {
            RequestComplexity.COMPLEX -> {
                Timber.tag("LlmRouter").d("Route=cloud/primary, model=${cloud.model}")
                RoutingDecision.Cloud(cloud, "complex task → primary model ${cloud.model}")
            }
            RequestComplexity.SIMPLE -> {
                val target = if (specialised.isAvailable()) specialised else cloud
                Timber.tag("LlmRouter").d("Route=cloud/specialised, model=${target.model}")
                RoutingDecision.Cloud(target, "simple task → specialised model ${target.model}")
            }
        }
    }
}
