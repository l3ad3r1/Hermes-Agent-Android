package com.hermes.agent.ui.terminal

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import timber.log.Timber

/**
 * An in-app terminal backed by Termux's terminal engine
 * (github.com/termux/termux-app — terminal-view + terminal-emulator).
 *
 * Runs Android's `/system/bin/sh` in a real pty inside the app sandbox. This is
 * the unrooted device shell (no Termux Linux packages); installing the full
 * Termux bootstrap is a follow-up. The view drives emulator sizing on layout.
 */
@Composable
fun TermuxTerminal(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val home = remember { context.filesDir.absolutePath }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val terminalView = TerminalView(ctx, null)
            terminalView.setTerminalViewClient(HermesTerminalViewClient(terminalView))
            terminalView.setTypeface(Typeface.MONOSPACE)
            terminalView.setTextSize(34)

            val env = arrayOf(
                "HOME=$home",
                "TMPDIR=${ctx.cacheDir.absolutePath}",
                "TERM=xterm-256color",
                "PATH=/system/bin:/system/xbin",
                "LANG=en_US.UTF-8",
            )
            val session = TerminalSession(
                /* shellPath = */ "/system/bin/sh",
                /* cwd = */ home,
                /* args = */ arrayOf("/system/bin/sh"),
                /* env = */ env,
                /* transcriptRows = */ 2000,
                /* client = */ HermesTerminalSessionClient(terminalView),
            )
            terminalView.attachSession(session)
            terminalView.isFocusableInTouchMode = true
            terminalView.requestFocus()
            terminalView
        },
        onRelease = { view -> view.currentSession?.finishIfRunning() },
    )
}

/** Routes session output back to the view and logs via Timber. No-op for the rest. */
private class HermesTerminalSessionClient(
    private val view: TerminalView,
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        if (view.currentSession == changedSession) view.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Timber.tag(tag ?: "Terminal").e(message) }
    override fun logWarn(tag: String?, message: String?) { Timber.tag(tag ?: "Terminal").w(message) }
    override fun logInfo(tag: String?, message: String?) { Timber.tag(tag ?: "Terminal").i(message) }
    override fun logDebug(tag: String?, message: String?) { Timber.tag(tag ?: "Terminal").d(message) }
    override fun logVerbose(tag: String?, message: String?) { Timber.tag(tag ?: "Terminal").v(message) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Timber.tag(tag ?: "Terminal").e(e, message)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Timber.tag(tag ?: "Terminal").e(e) }
}

/** Minimal view client: default key handling, monospace input, Timber logging. */
private class HermesTerminalViewClient(
    private val view: TerminalView,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { Timber.tag(tag ?: "TermView").e(message) }
    override fun logWarn(tag: String?, message: String?) { Timber.tag(tag ?: "TermView").w(message) }
    override fun logInfo(tag: String?, message: String?) { Timber.tag(tag ?: "TermView").i(message) }
    override fun logDebug(tag: String?, message: String?) { Timber.tag(tag ?: "TermView").d(message) }
    override fun logVerbose(tag: String?, message: String?) { Timber.tag(tag ?: "TermView").v(message) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Timber.tag(tag ?: "TermView").e(e, message)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Timber.tag(tag ?: "TermView").e(e) }
}
