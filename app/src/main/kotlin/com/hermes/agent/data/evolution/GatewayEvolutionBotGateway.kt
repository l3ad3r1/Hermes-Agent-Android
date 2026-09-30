package com.hermes.agent.data.evolution

import com.hermes.agent.data.plugin.evolution.BotRunResult
import com.hermes.agent.data.plugin.evolution.EvolutionBotGateway
import com.hermes.agent.data.remote.BotProfileStore
import com.hermes.agent.data.remote.GatewayApiClient
import com.hermes.agent.data.remote.GatewayEvent
import com.hermes.agent.domain.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The builder and reviewer bots are profiles on the desktop Hermes gateway —
 * one might run a local LLM, another relay to Antigravity, Claude or Codex; the
 * phone does not care which. Each bot call is one gateway run: the charter goes
 * in as the run's ephemeral `instructions`, so nothing on the PC is edited.
 *
 * Evolution runs never get tool approvals from the phone: an approval request is
 * denied at once. The bots are asked for text, not for actions on the PC.
 */
@Singleton
class GatewayEvolutionBotGateway @Inject constructor(
    private val client: GatewayApiClient,
    private val settings: SettingsRepository,
) : EvolutionBotGateway {

    override suspend fun isConfigured(): Boolean =
        runCatching { settings.current().remoteGatewayUrl.isNotBlank() }.getOrDefault(false)

    override suspend fun run(profile: String, input: String, instructions: String): BotRunResult {
        if (!BotProfileStore.VALID.matches(profile)) return BotRunResult.Failed("invalid bot profile name")
        var runId: String? = null
        return try {
            withTimeout(RUN_TIMEOUT_MS) {
                val id = client.startRun(input = input, sessionId = null, profile = profile, instructions = instructions)
                runId = id
                foldRunEvents(client.streamRunEvents(id, profile)) { approval ->
                    runCatching { client.submitApproval(id, approved = false, requestId = approval.requestId, profile = profile) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            runId?.let { id -> withContext(NonCancellable) { runCatching { client.stopRun(id, profile) } } }
            BotRunResult.Failed("the bot did not finish within ${RUN_TIMEOUT_MS / 60_000} minutes")
        } catch (e: CancellationException) {
            runId?.let { id -> withContext(NonCancellable) { runCatching { client.stopRun(id, profile) } } }
            throw e
        } catch (e: Exception) {
            Timber.tag("EvolutionGateway").w(e, "bot run failed on %s", profile)
            BotRunResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        /** A builder writing and revising a module can take a while on a local LLM. */
        const val RUN_TIMEOUT_MS = 20L * 60 * 1000
    }
}
