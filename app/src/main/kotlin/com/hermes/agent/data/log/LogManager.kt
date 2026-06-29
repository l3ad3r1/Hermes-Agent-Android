package com.hermes.agent.data.log

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures app logs to a size-capped file in private storage so the user can
 * view, copy, and share them from Settings → Logs (handy for reporting issues).
 *
 * Written to by [FileLogTree] on every Timber log (all build types). Access is
 * synchronized because Timber logs from many threads. The file is rotated
 * (oldest half dropped) once it exceeds [MAX_BYTES].
 */
@Singleton
class LogManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "hermes.log")
    private val lock = Any()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun append(priority: Int, tag: String?, message: String) {
        val line = "${timeFmt.format(Date())} ${level(priority)}/${tag ?: "-"}: $message\n"
        synchronized(lock) {
            try {
                if (file.length() > MAX_BYTES) rotate()
                file.appendText(line)
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    /** Recent log text (tail), capped at [maxChars] to keep the UI responsive. */
    fun readRecent(maxChars: Int = 200_000): String = synchronized(lock) {
        if (!file.exists()) return "(no logs captured yet)"
        val text = runCatching { file.readText() }.getOrDefault("")
        if (text.isBlank()) return "(no logs captured yet)"
        if (text.length <= maxChars) text else "…\n" + text.substring(text.length - maxChars)
    }

    fun clear() = synchronized(lock) { runCatching { file.writeText("") } }

    private fun rotate() {
        val text = runCatching { file.readText() }.getOrDefault("")
        runCatching { file.writeText(text.substring(text.length / 2)) }
    }

    private fun level(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024 // 512 KB
    }
}
