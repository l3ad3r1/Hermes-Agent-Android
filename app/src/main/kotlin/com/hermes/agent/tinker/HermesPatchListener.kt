package com.hermes.agent.tinker

import android.content.Context
import com.hermes.agent.data.hotfix.HotfixEvent
import com.hermes.agent.data.hotfix.HotfixPlatform
import com.hermes.agent.data.hotfix.PatchAdmission
import com.hermes.agent.data.hotfix.PatchGate
import com.tencent.tinker.lib.listener.DefaultPatchListener
import com.tencent.tinker.loader.shareutil.SharePatchFileUtil
import java.io.File

/**
 * The single door into Tinker: whatever calls `onPatchReceived`, the file must belong to a patch
 * the OTA flow verified ([PatchAdmission]: the staged download, or Tinker's own copy of the staged
 * or applied patch when it rebuilds compiled code), and it must pass [PatchGate] again (size,
 * SHA-256 from the release manifest, TINKER_ID, signed patch version, every entry signed by this
 * app's certificate) before Tinker's own checks run. There is no other way to feed Tinker a patch.
 */
class HermesPatchListener(context: Context) : DefaultPatchListener(context) {

    override fun patchCheck(path: String, patchMd5: String?): Int {
        val state = HotfixPlatform.stateStore(context).read()
        val file = File(path)
        val tinkerDirs = listOfNotNull(
            runCatching { SharePatchFileUtil.getPatchDirectory(context) }.getOrNull(),
            runCatching { SharePatchFileUtil.getPatchTempDirectory(context) }.getOrNull(),
        )
        val expected = PatchAdmission.expectedFor(state, file, patchMd5, tinkerDirs)
            ?: return reject(ERROR_NOT_STAGED, "not a fix verified by the update check")
        val verdict = PatchGate.verify(
            file,
            expected,
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
