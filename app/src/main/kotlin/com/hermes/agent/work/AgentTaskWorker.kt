package com.hermes.agent.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.model.ActivityEntry
import com.hermes.agent.domain.model.ActivityKind
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
    private val activityLedger: ActivityLedger,
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
            // Run the full agent pipeline (tools, plans, bounded loop) as a
            // BACKGROUND turn: ToolExecutionPolicy denies never-autonomous and
            // confirmation-required tools outright, so the delegated subagent
            // gets exactly the filtered tool subset with nobody to ask.
            val sb = StringBuilder()
            var finalText: String? = null
            var failure: String? = null
            chatRepository.sendMessageOrchestrated(convId, prompt, ExecutionOrigin.BACKGROUND)
                .collect { event ->
                    when (event) {
                        is OrchestratorEvent.ReplyToken -> sb.append(event.text)
                        is OrchestratorEvent.ReplyComplete -> finalText = event.finalText
                        is OrchestratorEvent.Failed -> failure = event.message
                        else -> { /* plan/tool events are persisted elsewhere */ }
                    }
                }
            failure?.let { error(it) }
            val result = (finalText ?: sb.toString()).take(300).ifBlank { "Task completed." }
            agentTaskRepository.markCompleted(taskId, result)
            activityLedger.record(
                ActivityEntry(
                    timestamp = System.currentTimeMillis(),
                    kind = ActivityKind.DELEGATION,
                    origin = ExecutionOrigin.BACKGROUND.name.lowercase(),
                    conversationId = convId,
                    title = label,
                    detail = result,
                    success = true,
                ),
            )
            postNotification(label, result, success = true)
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "AgentTaskWorker failed: $taskId")
            val reason = e.message ?: "Unknown error"
            agentTaskRepository.markFailed(taskId, reason)
            activityLedger.record(
                ActivityEntry(
                    timestamp = System.currentTimeMillis(),
                    kind = ActivityKind.DELEGATION,
                    origin = ExecutionOrigin.BACKGROUND.name.lowercase(),
                    conversationId = null,
                    title = label,
                    detail = reason,
                    success = false,
                ),
            )
            // A delegated task the user pocketed the phone for must not fail
            // silently — tell them, with the reason, so they can retry from the
            // Delegate screen (L-007). No retry: a re-run would re-notify.
            postNotification(label, reason, success = false)
            Result.failure()
        }
    }

    private fun postNotification(label: String, summary: String, success: Boolean) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Delegated Tasks", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val title = if (success) "✓ $label" else "✗ $label failed"
        nm.notify(
            label.hashCode() + 1000,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .build(),
        )
    }
}
