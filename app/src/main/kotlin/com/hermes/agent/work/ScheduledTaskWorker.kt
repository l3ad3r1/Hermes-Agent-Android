package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.proactive.ProactiveNotifier
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.proactive.ProactiveSource
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.domain.repository.ConversationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Executes a single [com.hermes.agent.domain.model.ScheduledTask] via the
 * chat repository, then posts a notification with the result summary.
 *
 * Input data keys:
 *   TASK_ID   — the ScheduledTask id to run
 *   TASK_PROMPT — the natural-language prompt
 *   TASK_LABEL  — display name for the notification
 */
@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepository,
    private val cronRepository: CronRepository,
    private val proactiveNotifier: ProactiveNotifier,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID     = "task_id"
        const val KEY_TASK_PROMPT = "task_prompt"
        const val KEY_TASK_LABEL  = "task_label"
        const val KEY_TASK_CRON   = "task_cron"
    }

    override suspend fun doWork(): Result {
        val taskId     = inputData.getString(KEY_TASK_ID)     ?: return Result.failure()
        val prompt     = inputData.getString(KEY_TASK_PROMPT)  ?: return Result.failure()
        val label      = inputData.getString(KEY_TASK_LABEL)   ?: "Scheduled task"
        val cron       = inputData.getString(KEY_TASK_CRON)

        // Day-of-week gate: a 24h WorkManager period can't express
        // "weekdays only" (0 8 * * 1-5), so the filter is enforced here.
        if (cron != null && !CronTiming.shouldRunNow(cron)) {
            Timber.d("ScheduledTaskWorker: '$label' skipped — day-of-week filter ($cron)")
            return Result.success()
        }

        Timber.d("ScheduledTaskWorker: running '$label'")

        return try {
            // Create a throw-away conversation for this run.
            val convId = conversationRepository.createConversation(label)

            // Scheduled work uses the same orchestrator as an interactive turn,
            // but the BACKGROUND policy limits tools that cannot run headlessly.
            val tokens = StringBuilder()
            var finalText: String? = null
            chatRepository.sendMessageOrchestrated(
                conversationId = convId,
                content = prompt,
                origin = ExecutionOrigin.BACKGROUND,
            ).collect { event ->
                when (event) {
                    is OrchestratorEvent.ReplyToken -> tokens.append(event.text)
                    is OrchestratorEvent.ReplyComplete -> finalText = event.finalText
                    is OrchestratorEvent.Failed -> throw IllegalStateException(event.message)
                    else -> Unit
                }
            }
            val result = (finalText ?: tokens.toString()).take(200).ifBlank { "Task completed." }

            cronRepository.recordRun(taskId, result)
            // Route through the proactive gate (roadmap v0.12): the annoyance
            // budget may suppress the ping, but the run and its result are
            // already durable in cron history above — quieter, never lost.
            proactiveNotifier.post(ProactiveSource.SCHEDULED_TASK, label, result)

            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A stopped worker is not a failed task; do not record it as one.
            throw e
        } catch (e: Exception) {
            Timber.e(e, "ScheduledTaskWorker failed for task $taskId")
            cronRepository.recordRun(taskId, "Error: ${e.message}")
            // No retry: a retry re-runs the whole agent turn (cost, side effects, another
            // conversation) within the period, and most failures repeat anyway. The next
            // scheduled run is the retry.
            Result.success()
        }
    }

}
