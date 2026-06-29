package com.hermes.agent.data.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Runs commands in the user's separately-installed **Termux** app via its
 * `RUN_COMMAND` intent, returning stdout/stderr/exit-code through a result
 * PendingIntent. This gives the agent the full Termux Linux environment
 * (apt/pkg, python, git, …) via the real Termux app, rather than the device's
 * limited `/system/bin/sh`.
 *
 * Requires: Termux installed, the `com.termux.permission.RUN_COMMAND` permission
 * (declared in the manifest), and `allow-external-apps=true` in the user's
 * `~/.termux/termux.properties`.
 */
@Singleton
class TermuxCommandRunner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0); true
    }.getOrDefault(false)

    /** The runtime permission Termux requires callers to hold (it's `dangerous`). */
    val runCommandPermission: String get() = PERMISSION_RUN_COMMAND

    /**
     * Launches [command] in a **foreground** Termux session (opens Termux and
     * shows it running) — fire-and-forget, for long/interactive flows like the
     * Hermes installer or starting the agent. Returns null on success, or a
     * human-readable error explaining what to fix.
     */
    fun launchSession(command: String): String? {
        if (!isTermuxInstalled()) {
            return "Termux is not installed. Install Termux from F-Droid (not the Play Store build)."
        }
        val service = Intent().apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            action = ACTION_RUN_COMMAND
            putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX/bin/bash")
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, "$TERMUX_FILES/home")
            putExtra(EXTRA_BACKGROUND, false) // foreground: visible Termux session
            putExtra(EXTRA_SESSION_ACTION, "0") // open Termux & switch to new session
            putExtra(EXTRA_COMMAND_LABEL, "Hermes")
        }
        return try {
            ContextCompat.startForegroundService(context, service)
            null
        } catch (t: Throwable) {
            Timber.tag("Termux").w(t, "launchSession failed")
            "Couldn't reach Termux (${t.javaClass.simpleName}: ${t.message}). " +
                "Grant the \"Run commands in Termux\" permission, and set " +
                "allow-external-apps=true in ~/.termux/termux.properties."
        }
    }

    /** Runs [command] in Termux bash and returns a human-readable result string. */
    suspend fun run(command: String, timeoutMs: Long = 60_000): String {
        if (!isTermuxInstalled()) {
            return "Termux is not installed. Install it from F-Droid/GitHub to use this tool."
        }

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<String> { cont ->
                val action = "$RESULT_ACTION_PREFIX${UUID.randomUUID()}"
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        runCatching { context.unregisterReceiver(this) }
                        val bundle = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE)
                        val stdout = bundle?.getString(RESULT_STDOUT).orEmpty().trim()
                        val stderr = bundle?.getString(RESULT_STDERR).orEmpty().trim()
                        val exit = bundle?.getInt(RESULT_EXIT_CODE, -1) ?: -1
                        val err = bundle?.getInt(RESULT_ERR, 0) ?: 0
                        val errmsg = bundle?.getString(RESULT_ERRMSG).orEmpty().trim()
                        if (cont.isActive) cont.resume(format(stdout, stderr, exit, err, errmsg))
                    }
                }
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pendingIntent = PendingIntent.getBroadcast(
                    context, 0, Intent(action).setPackage(context.packageName), piFlags,
                )

                val service = Intent().apply {
                    setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                    this.action = ACTION_RUN_COMMAND
                    putExtra(EXTRA_COMMAND_PATH, "$TERMUX_PREFIX/bin/bash")
                    putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                    putExtra(EXTRA_WORKDIR, "$TERMUX_FILES/home")
                    putExtra(EXTRA_BACKGROUND, true)
                    putExtra(EXTRA_PENDING_INTENT, pendingIntent)
                    putExtra(EXTRA_COMMAND_LABEL, "Hermes Agent")
                }

                try {
                    ContextCompat.startForegroundService(context, service)
                } catch (t: Throwable) {
                    Timber.tag("Termux").w(t, "RUN_COMMAND start failed")
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(
                        "Failed to reach Termux: ${t.message ?: t.javaClass.simpleName}. " +
                            "Ensure Termux is installed and `allow-external-apps=true` is set in " +
                            "~/.termux/termux.properties.",
                    )
                }
            }
        }
        return result ?: "Termux command timed out after ${timeoutMs}ms."
    }

    private fun format(stdout: String, stderr: String, exit: Int, err: Int, errmsg: String): String {
        if (err != 0) {
            return "Termux plugin error (err=$err): ${errmsg.ifBlank { "unknown" }}. " +
                "Check that `allow-external-apps=true` is set in ~/.termux/termux.properties."
        }
        return buildString {
            append("exit_code=$exit")
            if (stdout.isNotEmpty()) append("\n").append(stdout)
            if (stderr.isNotEmpty()) append("\n[stderr]\n").append(stderr)
        }
    }

    private companion object {
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_FILES = "/data/data/com.termux/files"
        const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_RESULT_BUNDLE = "result"
        const val RESULT_STDOUT = "stdout"
        const val RESULT_STDERR = "stderr"
        const val RESULT_EXIT_CODE = "exitCode"
        const val RESULT_ERR = "err"
        const val RESULT_ERRMSG = "errmsg"
        const val RESULT_ACTION_PREFIX = "com.hermes.agent.TERMUX_RESULT."
    }
}
