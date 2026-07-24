package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.proactive.ProactiveNotifier
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.proactive.ProactiveSource
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.domain.repository.ConversationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
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

            // Collect the streamed reply; Complete carries the final
            // persisted message, tokens are the fallback if it never arrives.
            val tokens = StringBuilder()
            var finalText: String? = null
            chatRepository.sendMessage(convId, prompt).collect { event ->
                when (event) {
                    is ChatStreamEvent.Token -> tokens.append(event.text)
                    is ChatStreamEvent.Complete -> finalText = event.message.content
                    is ChatStreamEvent.Error -> throw event.throwable
                }
            }
            val result = (finalText ?: tokens.toString()).take(200).ifBlank { "Task completed." }

            cronRepository.recordRun(taskId, result)
            // Route through the proactive gate (roadmap v0.12): the annoyance
            // budget may suppress the ping, but the run and its result are
            // already durable in cron history above — quieter, never lost.
            proactiveNotifier.post(ProactiveSource.SCHEDULED_TASK, label, result)

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "ScheduledTaskWorker failed for task $taskId")
            cronRepository.recordRun(taskId, "Error: ${e.message}")
            Result.retry()
        }
    }

}
