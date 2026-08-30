package com.hermes.agent.plugin.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class TaskerPluginReceiver : BroadcastReceiver() {

    @Inject
    lateinit var orchestrator: Orchestrator

    @Inject
    lateinit var voiceOutputManager: VoiceOutputManager

    @Inject
    lateinit var hostAuthority: TaskerHostAuthority

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskerBundleHelper.ACTION_FIRE_SETTING) return

        val config = TaskerBundleHelper.fromIntent(intent)

        // A broadcast carries no sender identity, so the token minted during
        // configuration is the only evidence that this fire came from a host the
        // user approved. No token, no run — this receiver drives the agent, and
        // before this check any installed app could have fired it.
        val host = hostAuthority.hostForToken(config.hostToken)
        if (host == null) {
            Timber.w(
                "TaskerPluginReceiver: refused a fire with %s host token",
                if (config.hostToken.isBlank()) "no" else "an unrecognised",
            )
            return
        }

        if (config.promptTemplate.isBlank()) {
            Timber.w("TaskerPluginReceiver: %s sent an empty prompt template", host)
            return
        }

        Timber.i("TaskerPluginReceiver: running a task for approved host %s", host)

        val pendingResult = runCatching { goAsync() }.getOrNull()

        scope.launch {
            try {
                val (finalResponse, exitCode) = executeTask(config)

                val resultExtras = Bundle().apply {
                    putString(TaskerBundleHelper.VAR_HERMES_RESULT, finalResponse)
                    putInt(TaskerBundleHelper.VAR_HERMES_EXIT_CODE, exitCode)
                }
                runCatching { setResultExtras(resultExtras) }
            } catch (t: Throwable) {
                Timber.e(t, "TaskerPluginReceiver: Error executing task")
                val resultExtras = Bundle().apply {
                    putString(TaskerBundleHelper.VAR_HERMES_RESULT, "Error: ${t.message}")
                    putInt(TaskerBundleHelper.VAR_HERMES_EXIT_CODE, 1)
                }
                runCatching { setResultExtras(resultExtras) }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    suspend fun executeTask(config: TaskerBundleHelper.TaskerConfig): Pair<String, Int> {
        val conversationId = UUID.randomUUID().toString()
        val timeoutMs = config.timeoutSeconds * 1000L

        var finalResponse = ""
        var exitCode = 0

        val outcome = withTimeoutOrNull(timeoutMs) {
            val flow = orchestrator.run(
                conversationId = conversationId,
                userMessage = config.promptTemplate,
                recentMessages = emptyList(),
                origin = ExecutionOrigin.BACKGROUND,
            )

            flow.collect { event ->
                when (event) {
                    is OrchestratorEvent.ReplyComplete -> {
                        finalResponse = event.finalText
                        exitCode = 0
                    }
                    is OrchestratorEvent.Failed -> {
                        finalResponse = event.message
                        exitCode = 1
                    }
                    else -> {}
                }
            }
        }

        if (outcome == null) {
            finalResponse = "Execution timed out after ${config.timeoutSeconds}s"
            exitCode = 2
        }

        if (config.speakResponse && finalResponse.isNotBlank() && exitCode == 0) {
            voiceOutputManager.speak(finalResponse)
        }

        return Pair(finalResponse, exitCode)
    }
}
