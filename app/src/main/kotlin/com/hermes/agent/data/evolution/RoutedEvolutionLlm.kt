package com.hermes.agent.data.evolution

import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingContext
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.plugin.evolution.EvolutionLlm
import com.hermes.agent.domain.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The proposal pass's model call, routed the same way as [ReflectivePromptRefiner]:
 * across every configured cloud provider with failover, never the on-device
 * model (`cloudOnly`) — a weak local model planning changes to the app is worse
 * than no plan.
 */
@Singleton
class RoutedEvolutionLlm @Inject constructor(
    private val llmRouter: LlmRouter,
) : EvolutionLlm {

    override suspend fun complete(system: String, user: String): Result<String> {
        val messages = listOf(
            LlmMessage(role = "system", content = system),
            LlmMessage(role = "user", content = user),
        )
        val decision = llmRouter.route(messages, RoutingContext(cloudOnly = true))
        if (decision is RoutingDecision.Unavailable) return Result.failure(IllegalStateException(decision.reason))
        return try {
            Result.success(decision.provider.complete(messages).content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("FeatureEvolution").w(e, "proposal model call failed")
            Result.failure(IllegalStateException("Every configured cloud model failed — check Settings → Cloud, then try again."))
        }
    }
}
