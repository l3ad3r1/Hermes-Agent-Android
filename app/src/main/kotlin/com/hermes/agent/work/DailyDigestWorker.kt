package com.hermes.agent.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.hermes.agent.data.butler.BriefingComposer
import com.hermes.agent.data.proactive.NotificationCaptureStore
import com.hermes.agent.data.proactive.ProactiveNotifier
import com.hermes.agent.domain.proactive.ProactiveSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Daily digest ping (roadmap v0.12): reuses the briefing composer's
 * weather/calendar/todos context as the digest body and routes it through
 * the proactive gate, so consent, DND, quiet hours, and the annoyance
 * budget all apply. Opt-in only — [ProactiveSource.DIGEST] defaults off.
 */
@HiltWorker
class DailyDigestWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val briefingComposer: BriefingComposer,
    private val proactiveNotifier: ProactiveNotifier,
    private val captureStore: NotificationCaptureStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val body = buildString {
            append(briefingComposer.composeContext(applicationContext).trim())
            append(notificationSection())
        }.trim().ifBlank { "Nothing on the radar today." }.take(1_500)
        proactiveNotifier.post(ProactiveSource.DIGEST, "Your daily digest", body)
        Result.success()
    } catch (e: Exception) {
        Timber.tag("DailyDigest").w(e, "digest composition failed")
        Result.retry()
    }

    /**
     * Opt-in notification summary. Injection boundary (L-009): the sanitized
     * captured text renders only into this human-facing digest — it must
     * never be fed to an LLM prompt.
     */
    private fun notificationSection(): String {
        if (!captureStore.captureEnabled) return ""
        val since = System.currentTimeMillis() - CAPTURE_WINDOW_MS
        val entries = captureStore.entriesSince(since)
        if (entries.isEmpty()) return ""
        val lines = entries.takeLast(MAX_DIGEST_NOTIFICATIONS).joinToString("\n") { n ->
            "- [${n.app.substringAfterLast('.')}] ${n.title}" +
                if (n.text.isNotBlank()) ": ${n.text}" else ""
        }
        val more = (entries.size - MAX_DIGEST_NOTIFICATIONS).coerceAtLeast(0)
        return "\n\nNotifications (last 24h):\n$lines" +
            if (more > 0) "\n- and $more more" else ""
    }

    companion object {
        const val UNIQUE_NAME = "proactive_daily_digest"
        const val CAPTURE_WINDOW_MS = 24L * 60 * 60 * 1000
        const val MAX_DIGEST_NOTIFICATIONS = 8
    }
}
