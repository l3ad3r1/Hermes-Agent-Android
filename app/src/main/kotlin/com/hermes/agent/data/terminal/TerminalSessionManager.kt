package com.hermes.agent.data.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns a single shared Termux [TerminalSession] (`/system/bin/sh`) used by both
 * the Terminal tab UI and the agent's [com.hermes.agent.data.tools.TerminalTool].
 *
 * Because the session is shared, commands the agent runs appear live in the
 * Terminal tab, and anything the user types is in the same shell. Command output
 * is captured by bracketing each command with sentinel markers and scraping the
 * emulator transcript.
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()

    @Volatile
    private var session: TerminalSession? = null

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            if (finishedSession == session) session = null
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { Timber.tag(tag ?: "Term").e(message) }
        override fun logWarn(tag: String?, message: String?) { Timber.tag(tag ?: "Term").w(message) }
        override fun logInfo(tag: String?, message: String?) { Timber.tag(tag ?: "Term").i(message) }
        override fun logDebug(tag: String?, message: String?) { Timber.tag(tag ?: "Term").d(message) }
        override fun logVerbose(tag: String?, message: String?) { Timber.tag(tag ?: "Term").v(message) }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Timber.tag(tag ?: "Term").e(e, message)
        }
        override fun logStackTrace(tag: String?, e: Exception?) { Timber.tag(tag ?: "Term").e(e) }
    }

    @Volatile
    private var busyboxBinDir: String? = null

    /**
     * Ensures BusyBox applet symlinks exist under filesDir/bin and returns that
     * dir, or null if BusyBox isn't bundled for this ABI. The static binary
     * ships as nativeLibraryDir/libbusybox.so (extracted via useLegacyPackaging).
     */
    @Synchronized
    private fun ensureBusybox(): String? {
        busyboxBinDir?.let { return it }
        val bb = java.io.File(context.applicationInfo.nativeLibraryDir, "libbusybox.so")
        if (!bb.exists()) return null
        val binDir = java.io.File(context.filesDir, "bin")
        val sentinel = java.io.File(binDir, "grep")
        if (!sentinel.exists()) {
            binDir.mkdirs()
            runCatching {
                ProcessBuilder(bb.absolutePath, "--install", "-s", binDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }.onFailure { Timber.tag("Term").w(it, "busybox --install failed") }
        }
        return if (sentinel.exists()) binDir.absolutePath.also { busyboxBinDir = it } else null
    }

    /** The shared session, creating (and headlessly initializing) it on first use. */
    @Synchronized
    fun getOrCreate(): TerminalSession {
        session?.let { if (it.isRunning) return it }
        val home = context.filesDir.absolutePath
        val bin = ensureBusybox()
        val path = if (bin != null) "$bin:/system/bin:/system/xbin" else "/system/bin:/system/xbin"
        val env = arrayOf(
            "HOME=$home",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "TERM=xterm-256color",
            "PATH=$path",
            "LANG=en_US.UTF-8",
            "PS1=$ ",
        )
        val s = TerminalSession(
            "/system/bin/sh", home, arrayOf("/system/bin/sh"), env, 5000, client,
        )
        // Initialize headlessly so the agent can run commands even before the
        // Terminal tab is opened; the TerminalView will updateSize() on attach.
        if (s.emulator == null) {
            s.initializeEmulator(80, 24, 12, 24)
        }
        session = s
        return s
    }

    private fun transcript(s: TerminalSession): String =
        s.emulator?.screen?.transcriptText ?: ""

    /**
     * Runs [command] in the shared shell and returns combined stdout+stderr,
     * prefixed with the exit code. Output is captured between unique markers so
     * it survives the prompt/echo noise of an interactive pty.
     */
    suspend fun run(command: String, timeoutMs: Long = 20_000): String = mutex.withLock {
        val s = getOrCreate()
        val nonce = System.nanoTime().toString(36)
        val start = "HERMES_BEGIN_$nonce"
        val end = "HERMES_END_$nonce"
        // Group so a multi-statement command's exit code is the last one.
        val line = "printf '%s\\n' $start; { $command ; } 2>&1; printf '%s%s\\n' $end \$?\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        s.write(bytes, 0, bytes.size)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val lines = transcript(s).split("\n")
            val endIdx = lines.indexOfLast { it.startsWith(end) }
            if (endIdx >= 0) {
                // Find the matching printed start marker before the end marker.
                val startIdx = lines.subList(0, endIdx)
                    .indexOfLast { it.trim() == start }
                val body = if (startIdx in 0 until endIdx) {
                    lines.subList(startIdx + 1, endIdx)
                } else {
                    emptyList()
                }
                val exit = lines[endIdx].removePrefix(end).trim()
                val output = body.joinToString("\n").trimEnd()
                return@withLock buildString {
                    append("exit_code=").append(exit.ifBlank { "?" })
                    if (output.isNotEmpty()) append('\n').append(output)
                }
            }
            delay(80)
        }
        "exit_code=?\n[timed out after ${timeoutMs}ms — command may still be running in the Terminal tab]"
    }
}
