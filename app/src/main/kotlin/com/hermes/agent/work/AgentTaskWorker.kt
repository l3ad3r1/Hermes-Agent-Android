package com.hermes.agent.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

@HiltWorker
class AgentTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val agentTaskRepository: AgentTaskRepository,
    private val chatRepository: ChatRepository,
    private val conversationRepository: ConversationRepository,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_TASK_ID     = "agent_task_id"
        const val KEY_TASK_LABEL  = "agent_task_label"
        const val KEY_TASK_PROMPT = "agent_task_prompt"
        const val CHANNEL_ID      = "hermes_delegate"
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)     ?: return Result.failure()
        val prompt = inputData.getString(KEY_TASK_PROMPT)  ?: return Result.failure()
        val label  = inputData.getString(KEY_TASK_LABEL)   ?: "Delegated task"

        Timber.d("AgentTaskWorker: running '$label'")
        agentTaskRepository.markRunning(taskId)

        return try {
            val convId = conversationRepository.createConversation(label)
            val sb = StringBuilder()
            chatRepository.sendMessage(convId, prompt).collect { event ->
                when (event) {
                    is ChatStreamEvent.Token    -> sb.append(event.text)
                    is ChatStreamEvent.Complete -> {}
                    is ChatStreamEvent.Error    -> throw event.throwable
                }
            }
            val result = sb.toString().take(300).ifBlank { "Task completed." }
            agentTaskRepository.markCompleted(taskId, result)
            postNotification(label, result)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AgentTaskWorker failed: $taskId")
            agentTaskRepository.markFailed(taskId, e.message ?: "Unknown error")
            Result.failure()
        }
    }

    private fun postNotification(label: String, summary: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Delegated Tasks", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        nm.notify(
            label.hashCode() + 1000,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✓ $label")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .build(),
        )
    }
}
