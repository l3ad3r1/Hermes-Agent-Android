package com.hermes.agent.data.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hermes.agent.domain.ledger.ActivityLedger
import com.hermes.agent.domain.model.ActivityEntry
import com.hermes.agent.domain.model.ActivityKind
import com.hermes.agent.domain.proactive.AnnoyanceBudget
import com.hermes.agent.domain.proactive.BudgetVerdict
import com.hermes.agent.domain.proactive.ProactiveSource
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single gate every proactive ping must pass (roadmap v0.12). Checks the
 * annoyance budget (consent, DND, quiet hours, daily and per-source caps),
 * posts the notification with a one-tap "Less of this" action, and records
 * the outcome — posted or suppressed — in the activity ledger so proactivity
 * is always auditable.
 *
 * Returns whether the ping was actually shown; callers must have their own
 * durable record of the content (cron history, conversation, …) so a
 * suppressed ping is quieter, never lost (L-007).
 */
@Singleton
class ProactiveNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: BudgetStateStore,
    private val activityLedger: ActivityLedger,
) {
    private val budget = AnnoyanceBudget(store)

    suspend fun post(source: ProactiveSource, title: String, text: String): Boolean {
        val verdict = budget.evaluate(
            source = source,
            date = LocalDate.now(),
            time = LocalTime.now(),
            dndActive = isDndActive(),
        )
        val posted = when (verdict) {
            is BudgetVerdict.Admit -> {
                notify(source, title, text)
                store.recordPing(source, LocalDate.now())
                true
            }
            is BudgetVerdict.Deny -> {
                Timber.tag("Proactive").i("suppressed '%s': %s", title, verdict.reason)
                false
            }
        }
        activityLedger.record(
            ActivityEntry(
                timestamp = System.currentTimeMillis(),
                kind = ActivityKind.PROACTIVE,
                origin = "background",
                conversationId = null,
                title = title,
                detail = when (verdict) {
                    is BudgetVerdict.Admit -> text.take(500)
                    is BudgetVerdict.Deny -> "suppressed: ${verdict.reason}"
                },
                success = posted,
            ),
        )
        return posted
    }

    private fun isDndActive(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun notify(source: ProactiveSource, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Proactive updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Scheduled tasks, digests, and nudges from Jeeves" },
            )
        }

        val lessIntent = Intent(context, LessOfThisReceiver::class.java)
            .setAction(LessOfThisReceiver.ACTION)
            .putExtra(LessOfThisReceiver.EXTRA_SOURCE, source.name)
        val lessPending = PendingIntent.getBroadcast(
            context,
            source.ordinal,
            lessIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        nm.notify(
            title.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .addAction(0, "Less of this", lessPending)
                .build(),
        )
    }

    companion object {
        const val CHANNEL_ID = "jeeves_proactive"
    }
}
