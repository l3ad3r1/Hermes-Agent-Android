package com.hermes.agent.tinker

import android.content.Context
import com.hermes.agent.data.hotfix.AppliedPatch
import com.hermes.agent.data.hotfix.HotfixEvent
import com.hermes.agent.data.hotfix.HotfixPlatform
import com.hermes.agent.data.hotfix.abandonInterruptedPreparation
import com.tencent.tinker.lib.reporter.DefaultLoadReporter
import com.tencent.tinker.lib.reporter.DefaultPatchReporter
import com.tencent.tinker.lib.tinker.Tinker
import com.tencent.tinker.lib.util.TinkerServiceInternals
import com.tencent.tinker.loader.shareutil.ShareConstants
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import java.io.File

/**
 * Records what Tinker did at start-up into the shared hot-fix state. Tinker's default behaviour
 * is kept (including disabling Tinker for this base after a dex/resource load exception).
 */
class HermesLoadReporter(context: Context) : DefaultLoadReporter(context) {

    override fun onLoadResult(patchDirectory: File?, loadCode: Int, cost: Long) {
        super.onLoadResult(patchDirectory, loadCode, cost)
        if (!ShareTinkerInternals.isInMainProcess(context)) return
        val store = HotfixPlatform.stateStore(context)
        val now = System.currentTimeMillis()
        when (loadCode) {
            ShareConstants.ERROR_LOAD_OK -> {
                val md5 = Tinker.with(context).tinkerLoadResultIfPresent?.currentVersion.orEmpty()
                store.update { s ->
                    val staged = s.staged
                    when {
                        staged != null && staged.md5.equals(md5, ignoreCase = true) -> s.copy(
                            applied = AppliedPatch(staged.patchVersion, staged.baseTinkerId, staged.md5, staged.notes, now, staged.sha256, staged.sizeBytes),
                            staged = null,
                            restartPending = false,
                            lastEvent = HotfixEvent(HotfixEvent.Kind.LOADED, "Fix #${staged.patchVersion} is active.", now, staged.patchVersion),
                        )
                        s.applied?.md5.equals(md5, ignoreCase = true) -> s.copy(restartPending = false)
                        else -> s.copy(
                            applied = AppliedPatch(-1, HotfixPlatform.installedTinkerId(context).orEmpty(), md5, "", now),
                            restartPending = false,
                        )
                    }
                }
            }
            ShareConstants.ERROR_LOAD_DISABLE, ShareConstants.ERROR_LOAD_PATCH_INFO_NOT_EXIST,
            ShareConstants.ERROR_LOAD_PATCH_DIRECTORY_NOT_EXIST -> store.update { s ->
                // Nothing loaded: forget a stale "applied" record (e.g. after Remove patch + restart).
                s.copy(applied = null, restartPending = s.staged?.installed == true)
            }
            else -> store.update { s ->
                s.copy(
                    applied = null,
                    lastEvent = HotfixEvent(HotfixEvent.Kind.LOAD_FAILED, "The fix could not be loaded (code $loadCode); Hermes runs the installed version.", now, s.staged?.patchVersion),
                )
            }
        }
        // A preparation whose :patch process died (killed, reboot) never reports back: without this
        // the card would show "Preparing…" forever and decideOta would never offer that fix again.
        val preparing = runCatching { TinkerServiceInternals.isTinkerPatchServiceRunning(context) }.getOrDefault(true)
        if (!preparing) {
            store.update { s -> abandonInterruptedPreparation(s, now) }
        }
    }
}

/** Runs in Tinker's `:patch` process while a patch is prepared. */
class HermesPatchReporter(context: Context) : DefaultPatchReporter(context) {

    override fun onPatchResult(patchFile: File?, success: Boolean, cost: Long) {
        super.onPatchResult(patchFile, success, cost)
        HotfixPlatform.stateStore(context).update { s ->
            val staged = s.staged ?: return@update s
            val now = System.currentTimeMillis()
            if (success) {
                s.copy(
                    staged = staged.copy(installed = true),
                    lastEvent = HotfixEvent(HotfixEvent.Kind.READY, "Fix #${staged.patchVersion} is ready. Restart Hermes to apply it.", now, staged.patchVersion),
                )
            } else {
                s.copy(
                    staged = null,
                    lastEvent = HotfixEvent(HotfixEvent.Kind.INSTALL_FAILED, "Fix #${staged.patchVersion} could not be prepared; nothing was changed.", now, staged.patchVersion),
                )
            }
        }
    }

    override fun onPatchPackageCheckFail(patchFile: File?, errorCode: Int) {
        super.onPatchPackageCheckFail(patchFile, errorCode)
        HotfixPlatform.stateStore(context).update {
            it.copy(lastEvent = HotfixEvent(HotfixEvent.Kind.REJECTED, "Tinker refused the fix (package check $errorCode).", System.currentTimeMillis(), it.staged?.patchVersion))
        }
    }
}
