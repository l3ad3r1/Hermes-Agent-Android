package com.hermes.agent.data.hotfix

import android.content.Context
import android.content.Intent
import android.os.Process
import com.hermes.agent.BuildConfig
import com.tencent.tinker.loader.shareutil.ShareConstants
import com.tencent.tinker.loader.shareutil.ShareTinkerInternals
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** What Settings → Updates shows about hot-fix patches. */
data class HotfixStatus(
    /** False when patches cannot be applied in this build/process; [unavailableReason] says why. */
    val available: Boolean,
    val unavailableReason: String? = null,
    /** Installed base build (manifest TINKER_ID). */
    val baseTinkerId: String?,
    /** Id compiled into the running code: differs from the base while a patch is loaded. */
    val runningTinkerId: String = BuildConfig.TINKER_ID,
    val active: AppliedPatch? = null,
    val staged: StagedPatch? = null,
    val restartPending: Boolean = false,
    val lastEvent: HotfixEvent? = null,
    val busy: Boolean = false,
    val progressPercent: Int? = null,
)

/**
 * Downloads, verifies and stages Tinker patches offered by the OTA channel, and removes them.
 * Every step needs a user action; nothing here restarts the app on its own.
 */
@Singleton
class HotfixManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    private val store = HotfixPlatform.stateStore(context)
    private val _status = MutableStateFlow(computeStatus(busy = false))
    val status: StateFlow<HotfixStatus> = _status.asStateFlow()

    /** Facts [decideOta] needs about this installation. */
    fun installedBuild(): InstalledBuild {
        val state = store.read()
        val tinker = HotfixPlatform.tinker(context)
        val tinkerId = HotfixPlatform.installedTinkerId(context)
        return InstalledBuild(
            versionName = BuildConfig.VERSION_NAME,
            tinkerId = tinkerId,
            // Only what was made for this base counts: after a full update, state left by the old base must
            // not hide or pre-empt the new base's patches (their numbers start again at 1).
            appliedPatchVersion = state.appliedVersionFor(tinkerId),
            stagedPatchVersion = state.stagedVersionFor(tinkerId),
            blockedPatchVersions = state.blockedFor(tinkerId),
            patchingAvailable = tinker != null && tinker.isTinkerEnabled &&
                ShareTinkerInternals.isTinkerEnableWithSharedPreferences(context),
        )
    }

    fun refresh() {
        _status.value = computeStatus(busy = _status.value.busy, progress = _status.value.progressPercent)
    }

    /**
     * Download [offer]'s patch into app-private storage, verify it ([PatchGate]) and hand it to
     * Tinker, which prepares it in its `:patch` process. It takes effect on the next start.
     */
    suspend fun stage(offer: PatchOffer): Result<Unit> = withContext(Dispatchers.IO) {
        if (_status.value.busy) return@withContext Result.failure(IllegalStateException("A fix is already being prepared."))
        _status.value = computeStatus(busy = true, progress = 0)
        val result = runCatching { stageInternal(offer) }
        result.onFailure { e ->
            Timber.tag(TAG).w(e, "patch %d not staged", offer.manifest.patchVersion)
            store.update {
                it.copy(lastEvent = HotfixEvent(HotfixEvent.Kind.REJECTED, e.message ?: "Fix could not be prepared", now(), offer.manifest.patchVersion))
            }
        }
        _status.value = computeStatus(busy = false)
        result
    }

    private fun stageInternal(offer: PatchOffer) {
        val m = offer.manifest
        val installed = installedBuild()
        check(installed.patchingAvailable) { "Hot-fixes are turned off on this installation." }
        check(TinkerIds.matches(installed.tinkerId, m.baseTinkerId)) { "This fix was made for a different build." }
        require(offer.patchUrl.startsWith("https://")) { "Refusing a non-HTTPS patch URL." }

        val inbox = HotfixPlatform.inboxDir(context).apply { mkdirs() }
        inbox.listFiles()?.forEach { it.delete() }
        val target = File(inbox, "hermes-patch-${m.patchVersion}.apk")
        val md5 = download(offer.patchUrl, target, m.size)

        val expected = ExpectedPatch(m.baseTinkerId, m.patchVersion, m.sha256, m.size)
        when (val verdict = PatchGate.verify(target, expected, installed.tinkerId, HotfixPlatform.signerSha256(context))) {
            is PatchGate.Verdict.Rejected -> {
                target.delete()
                error("Fix rejected: ${verdict.reason}")
            }
            PatchGate.Verdict.Accepted -> Unit
        }

        // Record what was verified first: HermesPatchListener re-runs PatchGate against this record
        // and refuses any file Tinker is handed that the OTA flow did not stage.
        store.update {
            it.copy(
                staged = StagedPatch(
                    patchVersion = m.patchVersion,
                    baseTinkerId = m.baseTinkerId,
                    sha256 = m.sha256,
                    md5 = md5,
                    sizeBytes = m.size,
                    notes = m.notes,
                    path = target.absolutePath,
                    minRestartPrompt = m.minRestartPrompt,
                    stagedAtMillis = now(),
                ),
                lastEvent = HotfixEvent(HotfixEvent.Kind.STAGED, "Preparing fix #${m.patchVersion}…", now(), m.patchVersion),
            )
        }
        val tinker = HotfixPlatform.tinker(context) ?: error("Hot-fix runtime is not running.")
        val code = tinker.patchListener.onPatchReceived(target.absolutePath)
        if (code != ShareConstants.ERROR_PATCH_OK) {
            store.update { it.copy(staged = null) }
            target.delete()
            error(listenerMessage(code))
        }
    }

    /** Streams [url] to [target] with a hard size cap; returns the file's MD5 (Tinker's patch version). */
    private fun download(url: String, target: File, expectedSize: Long): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Hermes/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/octet-stream")
            .build()
        val md5 = MessageDigest.getInstance("MD5")
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed (HTTP ${response.code})." }
            val body = response.body ?: error("Empty download.")
            var total = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        check(total <= expectedSize && total <= PatchManifest.MAX_PATCH_BYTES) { "Download is larger than the manifest says." }
                        md5.update(buf, 0, n)
                        out.write(buf, 0, n)
                        _status.value = _status.value.copy(progressPercent = ((total * 100) / expectedSize).toInt())
                    }
                }
            }
        }
        return with(PatchDigest) { md5.digest().toHex() }
    }

    /** Stop loading the current patch (and drop one that is waiting). Takes effect on restart. */
    suspend fun removePatch(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tinker = HotfixPlatform.tinker(context) ?: error("Hot-fix runtime is not running.")
            tinker.cleanPatch()
            HotfixPlatform.inboxDir(context).listFiles()?.forEach { it.delete() }
            store.update { s ->
                s.blocking(
                    s.applied?.let { BlockedPatch(it.baseTinkerId, it.patchVersion) },
                    s.staged?.let { BlockedPatch(it.baseTinkerId, it.patchVersion) },
                    limit = MAX_BLOCKED,
                ).copy(
                    staged = null,
                    restartPending = s.applied != null || s.staged?.installed == true,
                    lastEvent = HotfixEvent(
                        HotfixEvent.Kind.REMOVED,
                        "Patch removed. Restart Hermes to run the installed version again.",
                        now(),
                        s.applied?.patchVersion ?: s.staged?.patchVersion,
                    ),
                )
            }
            Unit
        }.also { refresh() }
    }

    /**
     * Relaunch Hermes so Tinker loads (or drops) the patch. Only ever called from a button: the
     * launcher activity is started in a new task and every Hermes process is ended.
     */
    fun restartNow() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) runCatching { context.startActivity(launch) }
        runCatching { ShareTinkerInternals.killAllOtherProcess(context) }
        Process.killProcess(Process.myPid())
    }

    private fun computeStatus(busy: Boolean, progress: Int? = null): HotfixStatus {
        val state = store.read()
        val tinker = HotfixPlatform.tinker(context)
        val reason = when {
            !BuildConfig.OTA_ENABLED -> "No update channel is configured in this build."
            tinker == null -> "The hot-fix runtime is not running in this build."
            !tinker.isTinkerEnabled || !ShareTinkerInternals.isTinkerEnableWithSharedPreferences(context) ->
                "Hot-fixes were switched off after a patch failed to load. Install the next full update to re-enable them."
            else -> null
        }
        return HotfixStatus(
            available = reason == null,
            unavailableReason = reason,
            baseTinkerId = HotfixPlatform.installedTinkerId(context),
            active = state.applied?.takeIf { tinker?.isTinkerLoaded == true },
            staged = state.staged,
            restartPending = state.restartPending || state.staged?.installed == true,
            lastEvent = state.lastEvent,
            busy = busy,
            progressPercent = progress,
        )
    }

    companion object {
        private const val TAG = "Hotfix"
        private const val MAX_BLOCKED = 32

        private fun now() = System.currentTimeMillis()

        fun listenerMessage(code: Int): String = when (code) {
            ShareConstants.ERROR_PATCH_DISABLE -> "Hot-fixes are turned off on this installation."
            ShareConstants.ERROR_PATCH_NOTEXIST -> "The downloaded fix is missing."
            ShareConstants.ERROR_PATCH_RUNNING -> "Another fix is being prepared; try again in a minute."
            ShareConstants.ERROR_PATCH_ALREADY_APPLY -> "This fix is already applied."
            ShareConstants.ERROR_PATCH_RETRY_COUNT_LIMIT -> "This fix failed to prepare too often and will not be retried."
            com.hermes.agent.tinker.HermesPatchListener.ERROR_NOT_STAGED -> "Refused: the fix was not verified by the update check."
            com.hermes.agent.tinker.HermesPatchListener.ERROR_GATE_REJECTED -> "Refused: the fix failed Hermes' signature/integrity checks."
            else -> "The fix could not be handed to the hot-fix runtime (code $code)."
        }
    }
}
