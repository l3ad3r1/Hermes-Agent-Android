package com.hermes.agent.tinker

import com.hermes.agent.data.hotfix.HotfixPlatform
import com.tencent.tinker.entry.ApplicationLike
import com.tencent.tinker.lib.library.TinkerLoadLibrary
import com.tencent.tinker.lib.patch.UpgradePatch
import com.tencent.tinker.lib.tinker.Tinker
import com.tencent.tinker.lib.tinker.TinkerInstaller
import timber.log.Timber

/** Installs the Tinker runtime with Hermes' reporters, trust-checking listener and result service. */
internal object HermesTinker {

    fun install(appLike: ApplicationLike) {
        val app = appLike.application
        runCatching {
            TinkerInstaller.install(
                appLike,
                HermesLoadReporter(app),
                HermesPatchReporter(app),
                HermesPatchListener(app),
                HermesPatchResultService::class.java,
                UpgradePatch(),
            )
        }.onFailure {
            // Never take the app down over the hot-fix runtime: without it Hermes runs as installed.
            Timber.tag("Hotfix").e(it, "Tinker install failed")
            return
        }
        val tinker = Tinker.with(app)
        if (tinker.isTinkerLoaded) {
            // Put the patch's native libraries (if any) ahead of the installed ones for
            // System.loadLibrary. llama.cpp's dlopen()ed ggml backends still resolve from the
            // installed nativeLibraryDir, which is why tools/tinker refuses llama.cpp changes.
            runCatching { TinkerLoadLibrary.installNavitveLibraryABI(app, "arm64-v8a") }
            PatchCrashGuard.install(app, HotfixPlatform.crashCounter(app))
        }
    }
}
