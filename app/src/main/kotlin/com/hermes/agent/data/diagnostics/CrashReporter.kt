package com.hermes.agent.data.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps one crash report on the phone and never sends it anywhere by itself.
 *
 * When the app dies from an uncaught exception, the handler writes a short report (version,
 * device, and the stack trace) to a private file. On the next launch the app shows it and asks;
 * only if the user chooses Share does it go out, through the normal share sheet. Choosing Dismiss
 * deletes it. Conversations, settings and credentials are never read.
 *
 * A stack trace can quote the message of the exception that was thrown, so the user is shown the
 * full text before deciding, not a summary of it.
 *
 * The idea, keeping the report local and sending only on confirmation, follows Agora
 * (github.com/newo-ether/Agora, MIT).
 */
object CrashReporter {

    private const val DIR = "crash"
    private const val FILE = "last-crash.txt"

    /** Only the newest crash is kept; an older unsent one is replaced. */
    fun install(context: Context, version: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, format(version, thread.name, error, System.currentTimeMillis())) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The unsent report from the last crash, or null. */
    fun pending(context: Context): String? =
        file(context).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun discard(context: Context) {
        file(context).delete()
    }

    internal fun write(context: Context, report: String) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText(report)
    }

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE)

    internal fun format(version: String, threadName: String, error: Throwable, nowMillis: Long): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(nowMillis))
        return buildString {
            appendLine("Hermes crash report")
            appendLine("Time: $time")
            appendLine("Hermes: $version")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: $threadName")
            appendLine()
            append(error.stackTraceToString())
        }
    }
}
