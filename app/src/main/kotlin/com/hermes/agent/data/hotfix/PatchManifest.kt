package com.hermes.agent.data.hotfix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `hermes-patch.json`, the small manifest published next to a Tinker patch on a GitHub release
 * (written by tools/tinker/build-patch). It says which installed build the patch was made for and
 * pins the patch file's SHA-256; it grants nothing by itself — the patch must still be signed with
 * this app's own certificate and carry the same TINKER_ID (see [PatchGate]).
 *
 * ```json
 * {"schema":1,"baseTinkerId":"hermes-90-1a2b3c4d5e6f","patchVersion":2,
 *  "asset":"hermes-patch-hermes-90-1a2b3c4d5e6f-2.apk","sha256":"<64 hex>","size":123456,
 *  "notes":"Fixes the calendar tool crash","minRestartPrompt":true}
 * ```
 */
@Serializable
data class PatchManifest(
    val schema: Int = 1,
    /** TINKER_ID of the installed build this patch applies to; anything else ignores it. */
    val baseTinkerId: String,
    /** Monotonic per base, starting at 1. A patch replaces any earlier one for the same base. */
    val patchVersion: Int,
    /** Release asset name of the patch; defaults to [defaultAssetName]. */
    val asset: String? = null,
    val sha256: String,
    /** Exact byte size of the patch file. */
    val size: Long,
    val notes: String = "",
    /**
     * true: ask the user to restart as soon as the patch is prepared. false: only show that it
     * takes effect the next time Hermes starts. Either way nothing restarts without the user.
     */
    val minRestartPrompt: Boolean = true,
    /** TINKER_ID of the fixed build the patch was diffed from (informational). */
    val newTinkerId: String? = null,
    @SerialName("baseVersionName") val baseVersionName: String? = null,
) {
    val assetName: String get() = asset ?: defaultAssetName(baseTinkerId, patchVersion)

    companion object {
        const val SCHEMA = 1
        const val MANIFEST_ASSET = "hermes-patch.json"

        /** Upper bound for a patch download; a patch bigger than this should be a full release. */
        const val MAX_PATCH_BYTES: Long = 64L * 1024 * 1024
        const val MAX_MANIFEST_BYTES: Int = 16 * 1024
        const val MAX_NOTES = 2_000

        private val TINKER_ID = Regex("^[A-Za-z][A-Za-z0-9._-]{0,127}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val ASSET = Regex("^[A-Za-z0-9._-]{1,200}\\.apk$")

        fun defaultAssetName(baseTinkerId: String, patchVersion: Int) = "hermes-patch-$baseTinkerId-$patchVersion.apk"

        /** True for release assets that may hold a patch manifest. */
        fun isManifestAsset(name: String): Boolean =
            name.startsWith("hermes-patch", ignoreCase = true) && name.endsWith(".json", ignoreCase = true)

        fun isValidTinkerId(id: String?): Boolean = id != null && TINKER_ID.matches(id)

        private val json = Json { ignoreUnknownKeys = true; isLenient = false }

        /** Parses and validates; a manifest that fails any check is rejected whole, never repaired. */
        fun parse(text: String): Result<PatchManifest> = runCatching {
            require(text.length <= MAX_MANIFEST_BYTES) { "manifest larger than $MAX_MANIFEST_BYTES bytes" }
            val m = json.decodeFromString(serializer(), text)
            require(m.schema == SCHEMA) { "unsupported manifest schema ${m.schema}" }
            require(isValidTinkerId(m.baseTinkerId)) { "invalid baseTinkerId" }
            require(m.newTinkerId == null || isValidTinkerId(m.newTinkerId)) { "invalid newTinkerId" }
            require(m.patchVersion >= 1) { "patchVersion must be >= 1" }
            require(SHA256.matches(m.sha256)) { "sha256 must be 64 lowercase hex characters" }
            require(m.size in 1..MAX_PATCH_BYTES) { "size out of range" }
            require(m.notes.length <= MAX_NOTES) { "notes longer than $MAX_NOTES characters" }
            require(ASSET.matches(m.assetName)) { "invalid asset name" }
            m
        }
    }
}
