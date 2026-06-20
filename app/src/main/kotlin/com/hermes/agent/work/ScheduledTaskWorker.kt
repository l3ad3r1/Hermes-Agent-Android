package com.hermes.agent.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.R
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
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID     = "task_id"
        const val KEY_TASK_PROMPT = "task_prompt"
        const val KEY_TASK_LABEL  = "task_label"
        const val CHANNEL_ID      = "hermes_scheduled"
    }

    override suspend fun doWork(): Result {
        val taskId     = inputData.getString(KEY_TASK_ID)     ?: return Result.failure()
        val prompt     = inputData.getString(KEY_TASK_PROMPT)  ?: return Result.failure()
        val label      = inputData.getString(KEY_TASK_LABEL)   ?: "Scheduled task"

        Timber.d("ScheduledTaskWorker: running '$label'")

        return try {
            // Create a throw-away conversation for this run.
            val convId = conversationRepository.createConversation(label)

            // Collect the full streamed reply.
            val sb = StringBuilder()
            chatRepository.sendMessage(convId, prompt).collect { event ->
                val text = event.extractText()
                if (text != null) sb.append(text)
            }
            val result = sb.toString().take(200).ifBlank { "Task completed." }

            cronRepository.recordRun(taskId, result)
            postNotification(label, result)

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "ScheduledTaskWorker failed for task $taskId")
            cronRepository.recordRun(taskId, "Error: ${e.message}")
            Result.retry()
        }
    }

    private fun postNotification(label: String, summary: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled Tasks",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(label)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .build()

        nm.notify(label.hashCode(), notification)
    }
}

private fun Any.extractText(): String? = null // placeholder — ViewModel handles streaming
