package com.hermes.agent.data.proactive

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CapturedNotification(
    val app: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)

/**
 * Bounded store for sanitized third-party notification summaries, feeding the
 * opt-in section of the daily digest (roadmap v0.12).
 *
 * Injection boundary (L-009): entries hold [ThirdPartySanitizer]-scrubbed
 * text and are rendered ONLY into the human-facing digest notification —
 * nothing here may ever be concatenated into an LLM prompt.
 *
 * Capture is off unless the user both flips the in-app switch AND grants
 * the system notification-access permission; either alone captures nothing.
 */
@Singleton
class NotificationCaptureStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var captureEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    @Synchronized
    fun add(entry: CapturedNotification) {
        val next = (load() + entry).takeLast(MAX_ENTRIES)
        prefs.edit().putString(KEY_ENTRIES, json.encodeToString(next)).apply()
    }

    /** Entries newer than [sinceMillis], oldest first. */
    fun entriesSince(sinceMillis: Long): List<CapturedNotification> =
        load().filter { it.timestamp >= sinceMillis }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun load(): List<CapturedNotification> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CapturedNotification>>(raw) }
            .onFailure { Timber.tag("NotifCapture").w(it, "corrupt capture store, resetting") }
            .getOrElse {
                clear()
                emptyList()
            }
    }

    companion object {
        const val PREFS = "notification_capture"
        const val KEY_ENABLED = "capture_enabled"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 50
    }
}
