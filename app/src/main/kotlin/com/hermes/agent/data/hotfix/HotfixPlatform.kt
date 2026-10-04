package com.hermes.agent.data.hotfix

import android.content.Context
import android.content.pm.PackageManager
import com.tencent.tinker.lib.tinker.Tinker
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import java.io.File

/**
 * The Android side of the hot-fix checks, shared by the main process ([HotfixManager]) and the
 * Tinker callbacks, which also run in the `:patch` process where there is no Hilt graph.
 */
object HotfixPlatform {
    fun dir(context: Context): File = File(context.filesDir, "hotfix")

    /** Downloads land here, app-private; nothing outside the app can write a patch into place. */
    fun inboxDir(context: Context): File = File(dir(context), "inbox")

    fun stateStore(context: Context): HotfixStateStore = HotfixStateStore(dir(context))

    fun crashCounter(context: Context): CrashCounterFile = CrashCounterFile(File(dir(context), "crash-count"))

    /** The installed manifest's TINKER_ID, read the same way Tinker's own check reads it. */
    fun installedTinkerId(context: Context): String? =
        runCatching { ShareTinkerInternals.getManifestTinkerID(context) }.getOrNull()

    /** SHA-256 of the installed package's signing certificate(s). */
    fun signerSha256(context: Context): Set<String> = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: return emptySet()
        // The current signer(s) only: a key that was rotated away may not sign patches.
        signing.apkContentsSigners.map { PatchDigest.sha256Hex(it.toByteArray()) }.toSet()
    }.getOrDefault(emptySet())

    /** Tinker is installed in this process and not disabled (e.g. after a load exception). */
    fun tinkerReady(): Boolean = runCatching { Tinker.isTinkerInstalled() }.getOrDefault(false)

    fun tinker(context: Context): Tinker? = if (tinkerReady()) runCatching { Tinker.with(context) }.getOrNull() else null
}
