package com.hermes.agent.tinker

import android.content.Context
import com.hermes.agent.data.hotfix.ExpectedPatch
import com.hermes.agent.data.hotfix.HotfixEvent
import com.hermes.agent.data.hotfix.HotfixPlatform
import com.hermes.agent.data.hotfix.PatchGate
import com.tencent.tinker.lib.listener.DefaultPatchListener
import java.io.File

/**
 * The single door into Tinker: whatever calls `onPatchReceived`, the file must be the one the OTA
 * flow downloaded and recorded as staged, and it must pass [PatchGate] again (size, SHA-256 from
 * the release manifest, TINKER_ID, every entry signed by this app's certificate) before Tinker's
 * own checks run. There is no other way to feed Tinker a patch in this app.
 */
class HermesPatchListener(context: Context) : DefaultPatchListener(context) {

    override fun patchCheck(path: String, patchMd5: String?): Int {
        val staged = HotfixPlatform.stateStore(context).read().staged
        val file = File(path)
        if (staged == null || File(staged.path).canonicalPath != file.canonicalPath) return reject(ERROR_NOT_STAGED, "not staged by the update check")
        if (patchMd5 == null || !patchMd5.equals(staged.md5, ignoreCase = true)) return reject(ERROR_NOT_STAGED, "file changed since it was verified")
        val verdict = PatchGate.verify(
            file,
            ExpectedPatch(staged.baseTinkerId, staged.patchVersion, staged.sha256, staged.sizeBytes),
            HotfixPlatform.installedTinkerId(context),
            HotfixPlatform.signerSha256(context),
        )
        if (verdict is PatchGate.Verdict.Rejected) return reject(ERROR_GATE_REJECTED, verdict.reason)
        return super.patchCheck(path, patchMd5)
    }

    private fun reject(code: Int, reason: String): Int {
        HotfixPlatform.stateStore(context).update {
            it.copy(lastEvent = HotfixEvent(HotfixEvent.Kind.REJECTED, "Fix refused: $reason", System.currentTimeMillis(), it.staged?.patchVersion))
        }
        return code
    }

    companion object {
        /** Outside Tinker's ShareConstants.ERROR_PATCH_* range (0 … -12). */
        const val ERROR_NOT_STAGED = -101
        const val ERROR_GATE_REJECTED = -102
    }
}
