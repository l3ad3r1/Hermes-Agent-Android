package com.hermes.agent.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hermes.agent.data.proactive.CapturedNotification
import com.hermes.agent.data.proactive.NotificationCaptureStore
import com.hermes.agent.data.proactive.ThirdPartySanitizer
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Opt-in notification capture for the daily digest (roadmap v0.12). Dormant
 * unless the user grants system notification access AND flips the in-app
 * switch — with either missing, nothing is stored.
 *
 * Injection boundary (L-009): text is sanitized on entry and used ONLY in
 * the human-facing digest — never in an LLM prompt.
 */
@AndroidEntryPoint
class JeevesNotificationListener : NotificationListenerService() {

    @Inject lateinit var store: NotificationCaptureStore

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!store.captureEnabled) return
        if (sbn.packageName == packageName) return // never digest our own pings
        if (sbn.isOngoing) return // media players, foreground services, etc.

        val extras = sbn.notification.extras
        val title = ThirdPartySanitizer.sanitize(
            extras.getCharSequence("android.title")?.toString(),
        )
        val text = ThirdPartySanitizer.sanitize(
            extras.getCharSequence("android.text")?.toString(),
        )
        if (title.isBlank() && text.isBlank()) return

        store.add(
            CapturedNotification(
                app = ThirdPartySanitizer.sanitize(sbn.packageName, maxChars = 60),
                title = title,
                text = text,
                timestamp = sbn.postTime,
            ),
        )
    }
}
