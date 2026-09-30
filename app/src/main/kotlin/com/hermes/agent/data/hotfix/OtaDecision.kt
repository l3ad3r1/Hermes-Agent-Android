package com.hermes.agent.data.hotfix

/** A full-APK release newer than the installed version. */
data class FullUpdate(
    val version: String,
    val releaseUrl: String,
    val releaseNotes: String,
    /** Direct APK asset URL, or "" when the release has none (then only the page is offered). */
    val apkUrl: String,
)

/** A parsed patch manifest found on a release, with the URL of its patch asset. */
data class PatchOffer(
    val manifest: PatchManifest,
    val patchUrl: String,
    val releaseTag: String,
)

/** What the installed build knows about itself when deciding between a patch and a full APK. */
data class InstalledBuild(
    val versionName: String,
    /** TINKER_ID from the installed manifest (what Tinker compares against); null if unknown. */
    val tinkerId: String?,
    /** Patch version currently loaded, 0 for none. */
    val appliedPatchVersion: Int = 0,
    /** Patch version prepared and waiting for a restart, 0 for none. */
    val stagedPatchVersion: Int = 0,
    /** Patch versions that were rolled back (crash loop) or removed by the user: never re-offered. */
    val blockedPatchVersions: Set<Int> = emptySet(),
    /** False when Tinker is not installed/enabled in this process: patches cannot be applied. */
    val patchingAvailable: Boolean = true,
)

sealed interface OtaDecision {
    data object None : OtaDecision
    data class FullApk(val update: FullUpdate) : OtaDecision

    /** Apply [offer] without reinstalling; [fullUpdate] is a newer full release, if there is one. */
    data class Patch(val offer: PatchOffer, val fullUpdate: FullUpdate?) : OtaDecision
}

/**
 * Patch vs full APK vs nothing. A patch is offered only for the exact installed base build, only
 * when it is newer than what is applied or waiting, and never a version that was rolled back or
 * removed. Without a matching patch a newer full release is offered as before.
 */
fun decideOta(installed: InstalledBuild, fullUpdate: FullUpdate?, offers: List<PatchOffer>): OtaDecision {
    val floor = maxOf(installed.appliedPatchVersion, installed.stagedPatchVersion)
    val patch = if (!installed.patchingAvailable) {
        null
    } else {
        offers.asSequence()
            .filter { TinkerIds.matches(installed.tinkerId, it.manifest.baseTinkerId) }
            .filter { it.patchUrl.isNotBlank() }
            .filter { it.manifest.patchVersion > floor }
            .filter { it.manifest.patchVersion !in installed.blockedPatchVersions }
            .maxByOrNull { it.manifest.patchVersion }
    }
    return when {
        patch != null -> OtaDecision.Patch(patch, fullUpdate)
        fullUpdate != null -> OtaDecision.FullApk(fullUpdate)
        else -> OtaDecision.None
    }
}
