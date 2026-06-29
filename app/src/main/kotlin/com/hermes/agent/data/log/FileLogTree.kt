package com.hermes.agent.data.log

import android.util.Log
import timber.log.Timber

/**
 * Timber tree that mirrors every log line into [LogManager]'s on-disk buffer
 * so logs are available in-app (Settings → Logs) on all build types.
 */
class FileLogTree(private val logManager: LogManager) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val full = if (t != null) "$message\n${Log.getStackTraceString(t)}" else message
        logManager.append(priority, tag, full)
    }
}
