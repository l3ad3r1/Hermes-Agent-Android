package com.hermes.agent.tinker

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import com.hermes.agent.data.hotfix.CrashCounterFile
import com.hermes.agent.data.hotfix.CrashLoopPolicy
import com.hermes.agent.data.hotfix.HotfixEvent
import com.hermes.agent.data.hotfix.HotfixPlatform
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals

/**
 * Removes a loaded patch that makes Hermes crash right after start ([CrashLoopPolicy]: three
 * crashes within ten seconds of start in a row). Installed only when a patch is loaded, in the main
 * process, and chained in front of the previous handler so Tinker's and the crash reporter still run.
 */
internal object PatchCrashGuard {
    private val policy = CrashLoopPolicy()

    fun install(app: Application, counter: CrashCounterFile) {
        if (!ShareTinkerInternals.isInMainProcess(app)) return
        val startedAt = SystemClock.elapsedRealtime()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val decision = policy.onCrash(SystemClock.elapsedRealtime() - startedAt, counter.read(), patchLoaded = true)
                counter.write(decision.newCount)
                if (decision.rollBack) rollBack(app)
            }
            previous?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
        }
        Handler(Looper.getMainLooper()).postDelayed({ counter.write(0) }, policy.quickCrashWindowMs)
    }

    private fun rollBack(app: Application) {
        ShareTinkerInternals.cleanPatch(app)
        HotfixPlatform.stateStore(app).update { s ->
            val version = s.applied?.patchVersion
            s.copy(
                applied = null,
                staged = null,
                blockedPatchVersions = (s.blockedPatchVersions + listOfNotNull(version)).distinct().takeLast(32),
                restartPending = false,
                lastEvent = HotfixEvent(
                    HotfixEvent.Kind.ROLLED_BACK,
                    "Fix${version?.let { " #$it" } ?: ""} was removed after Hermes crashed ${policy.maxQuickCrashes} times right after start.",
                    System.currentTimeMillis(),
                    version,
                ),
            )
        }
    }
}
