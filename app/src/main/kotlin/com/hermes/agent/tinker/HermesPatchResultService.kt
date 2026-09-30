package com.hermes.agent.tinker

import com.tencent.tinker.lib.service.AbstractResultService
import com.tencent.tinker.lib.service.PatchResult
import com.tencent.tinker.lib.util.TinkerServiceInternals
import java.io.File

/**
 * Tinker's patch result, delivered to the main process. The outcome itself is already recorded
 * by [HermesPatchReporter] in the `:patch` process (this service can be refused when the app is in
 * the background); here the `:patch` process is stopped and the downloaded file deleted.
 * Unlike Tinker's DefaultTinkerResultService this never kills the app: the user restarts.
 */
class HermesPatchResultService : AbstractResultService() {
    override fun onPatchResult(result: PatchResult?) {
        runCatching { TinkerServiceInternals.killTinkerPatchServiceProcess(applicationContext) }
        val raw = result?.rawPatchFilePath ?: return
        runCatching { File(raw).delete() }
    }
}
