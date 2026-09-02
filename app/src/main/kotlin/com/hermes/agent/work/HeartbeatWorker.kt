package com.hermes.agent.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.local.dao.ActivityLedgerDao
import com.hermes.agent.data.local.entity.ActivityLedgerEntity
import com.hermes.agent.data.notifications.NotificationGateway
import com.hermes.agent.data.presence.PresenceManager
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.model.StandingOrder
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Background Heartbeat Worker for proactive automation and Standing Orders.
 * Ported from OpenClaw heartbeat and proactive node specification.
 *
 * Enforces fail-silent, battery floor, and power-save invariants.
 */
@HiltWorker
class HeartbeatWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepository: SettingsRepository,
    private val presenceManager: PresenceManager,
    private val conversationRepository: ConversationRepository,
    private val chatRepository: ChatRepository,
    private val notificationGateway: NotificationGateway,
    private val activityLedgerDao: ActivityLedgerDao,
) : CoroutineWorker(context, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        return try {
            val settings = settingsRepository.current()
            if (!settings.heartbeatEnabled) {
                Timber.tag(TAG).d("Heartbeat disabled in settings; skipping run.")
                return Result.success()
            }

            if (isPowerSaveModeActive() || isBatteryFloorReached()) {
                Timber.tag(TAG).d("Heartbeat skipped due to battery / power-save mode.")
                return Result.success()
            }

            val presence = runCatching {
                presenceManager.captureSnapshot()
            }.getOrNull()

            if (presence == null || presence.contextSummary.isBlank()) {
                Timber.tag(TAG).d("Empty or failed presence snapshot; exiting heartbeat run silently.")
                return Result.success()
            }

            Timber.tag(TAG).d("Heartbeat running. Presence: %s", presence.contextSummary)

            val orders = parseOrders(settings.standingOrdersJson)
            if (orders.isEmpty()) {
                Timber.tag(TAG).d("No standing orders configured; exiting heartbeat run silently.")
                return Result.success()
            }

            val now = System.currentTimeMillis()
            val dueOrders = orders.filter { order ->
                val lastExec = order.lastExecutedAt
                order.enabled && (lastExec == null || (now - lastExec) >= order.intervalMinutes * 60 * 1000L)
            }

            if (dueOrders.isEmpty()) {
                Timber.tag(TAG).d("No standing orders due at this time.")
                return Result.success()
            }

            var updatedOrders = orders
            var executedCount = 0

            for (order in dueOrders) {
                try {
                    val convTitle = "Heartbeat: ${order.title}"
                    val convId = conversationRepository.createConversation(convTitle)
                    val prompt = buildString {
                        append("You are running a background heartbeat standing order.\n")
                        append("Current Context: ${presence.contextSummary}\n\n")
                        append("Standing Order: ${order.title}\n")
                        append("Instructions: ${order.instruction}\n\n")
                        append("If action or notification is needed, formulate your response clearly.")
                    }

                    val tokens = StringBuilder()
                    var finalText: String? = null
                    chatRepository.sendMessage(convId, prompt).collect { event ->
                        when (event) {
                            is ChatStreamEvent.Token -> tokens.append(event.text)
                            is ChatStreamEvent.Complete -> finalText = event.message.content
                            is ChatStreamEvent.Error -> throw event.throwable
                        }
                    }

                    val result = (finalText ?: tokens.toString()).trim()
                    if (result.isNotBlank() && !result.equals("OK", ignoreCase = true) && !result.equals("NO_ACTION", ignoreCase = true)) {
                        notificationGateway.postNotification(
                            title = order.title,
                            message = result.take(300),
                        )
                    }

                    updatedOrders = updatedOrders.map {
                        if (it.id == order.id) it.copy(lastExecutedAt = now, lastResult = result.take(200)) else it
                    }
                    executedCount++
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error evaluating standing order %s", order.id)
                }
            }

            if (executedCount > 0) {
                val encoded = json.encodeToString(ListSerializer(StandingOrder.serializer()), updatedOrders)
                settingsRepository.setStandingOrdersJson(encoded)
            }

            try {
                activityLedgerDao.insert(
                    ActivityLedgerEntity(
                        timestamp = now,
                        kindName = "HEARTBEAT",
                        origin = "BACKGROUND",
                        conversationId = null,
                        title = "Heartbeat evaluation",
                        detail = "Evaluated $executedCount standing orders with presence: ${presence.contextSummary}",
                        success = true,
                    )
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not write to activity ledger")
            }

            Result.success()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Heartbeat worker failed silently")
            Result.success() // Fail-silent
        }
    }

    private fun parseOrders(raw: String): List<StandingOrder> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StandingOrder.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun isPowerSaveModeActive(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isPowerSaveMode ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun isBatteryFloorReached(): Boolean {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else 100
            !isCharging && batteryPct <= 15
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        const val TAG = "HeartbeatWorker"
        const val UNIQUE_WORK_NAME = "hermes_periodic_heartbeat"
    }
}
