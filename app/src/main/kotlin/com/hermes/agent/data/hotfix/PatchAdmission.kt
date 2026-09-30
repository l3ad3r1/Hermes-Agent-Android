package com.hermes.agent.data.hotfix

import java.io.File

/**
 * Which verified record, if any, a file handed to Tinker belongs to. Used by
 * [com.hermes.agent.tinker.HermesPatchListener]; [PatchGate] then re-checks the file against it.
 *
 * Two callers reach Tinker's listener:
 * - the OTA flow, with the file it downloaded and recorded as [HotfixState.staged];
 * - Tinker itself, re-running the installed patch file from its own patch directory to rebuild
 *   the compiled (oat) code after the system discarded it (e.g. an Android system update). That
 *   file is a copy of a patch that already passed the gate, recorded as [HotfixState.applied].
 *
 * A match is by MD5 (Tinker's patch version name) *and* location; the returned record carries the
 * SHA-256 and size that [PatchGate] re-verifies, so a matching name alone admits nothing.
 */
object PatchAdmission {

    fun expectedFor(
        state: HotfixState,
        file: File,
        md5: String?,
        tinkerDirs: List<File>,
    ): ExpectedPatch? {
        if (md5.isNullOrBlank()) return null
        val path = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val inTinkerDir = tinkerDirs.any { dir -> runCatching { path.startsWith(dir.canonicalFile) }.getOrDefault(false) }

        state.staged?.let { s ->
            val isStagedFile = runCatching { File(s.path).canonicalFile == path }.getOrDefault(false)
            if (md5.equals(s.md5, ignoreCase = true) && (isStagedFile || inTinkerDir)) {
                return ExpectedPatch(s.baseTinkerId, s.patchVersion, s.sha256, s.sizeBytes)
            }
        }
        state.applied?.let { a ->
            if (inTinkerDir && a.patchVersion > 0 && a.sha256.isNotBlank() && a.sizeBytes > 0 &&
                md5.equals(a.md5, ignoreCase = true)
            ) {
                return ExpectedPatch(a.baseTinkerId, a.patchVersion, a.sha256, a.sizeBytes)
            }
        }
        return null
    }
}
