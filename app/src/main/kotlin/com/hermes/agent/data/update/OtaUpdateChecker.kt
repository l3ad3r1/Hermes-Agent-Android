package com.hermes.agent.data.update

import com.hermes.agent.BuildConfig
import com.hermes.agent.data.hotfix.FullUpdate
import com.hermes.agent.data.hotfix.HotfixManager
import com.hermes.agent.data.hotfix.OtaDecision
import com.hermes.agent.data.hotfix.PatchManifest
import com.hermes.agent.data.hotfix.PatchOffer
import com.hermes.agent.data.hotfix.decideOta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtaUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
    private val hotfixManager: HotfixManager,
) {

    /**
     * Test builds: releases the self-repair pipeline publishes as pre-releases before they are
     * promoted to "latest". Off by default; a device that turns it on is the canary.
     */
    var testBuilds: Boolean
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEST_BUILDS, false)
        set(value) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEST_BUILDS, value).apply()

    /**
     * What to offer: a hot-fix patch for exactly this installed build (see docs/TINKER-HOTFIX.md),
     * else a newer full APK, else nothing. Patches are looked for on this build's own release
     * (`v<versionName>`) and on the newest release; [decideOta] makes the choice.
     */
    suspend fun checkOffer(): OtaDecision = withContext(Dispatchers.IO) {
        // The single Hermes update channel (BuildConfig.UPDATE_REPO, from gradle.properties).
        // Blank means "no channel configured". Never fall back to the standalone
        // Hermes-Agent-Android or Octo-Jotter repos: their APKs carry a different
        // applicationId, so "updating" would install a SECOND app. See docs/UX_AUDIT.md JX-01.
        if (!BuildConfig.OTA_ENABLED || BuildConfig.UPDATE_REPO.isBlank()) {
            Timber.tag("OtaChecker").i("no update channel configured — skipping check")
            return@withContext OtaDecision.None
        }
        val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}"
        val newest = fetchText(if (testBuilds) "$api/releases?per_page=15" else "$api/releases/latest")
            ?.let { body -> runCatching { if (testBuilds) newestRelease(JSONArray(body)) else JSONObject(body) }.getOrNull() }

        val fullUpdate = newest?.let { fullUpdateFrom(it) }

        // Patches for this build normally hang off its own release.
        val baseTag = "v" + BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+')
        val baseRelease = if (newest?.optString("tag_name") == baseTag) {
            null
        } else {
            fetchText("$api/releases/tags/$baseTag")?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
        val offers = listOfNotNull(baseRelease, newest).flatMap { patchOffers(it) }

        decideOta(hotfixManager.installedBuild(), fullUpdate, offers).also {
            Timber.tag("OtaChecker").d("decision=%s current=%s", it::class.simpleName, BuildConfig.VERSION_NAME)
        }
    }

    private fun fullUpdateFrom(release: JSONObject): FullUpdate? {
        val remoteVersion = release.optString("tag_name", "").removePrefix("v")
        if (remoteVersion.isBlank() || !isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) return null
        return FullUpdate(
            version = remoteVersion,
            releaseUrl = release.optString("html_url", ""),
            releaseNotes = release.optString("body", "").take(500),
            apkUrl = firstApkAssetUrl(release),
        )
    }

    /** Every valid patch manifest attached to [release], paired with its patch asset's URL. */
    private fun patchOffers(release: JSONObject): List<PatchOffer> {
        val assets = release.optJSONArray("assets") ?: return emptyList()
        val byName = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .associateBy { it.optString("name", "") }
        val tag = release.optString("tag_name", "")
        return byName.filterKeys { PatchManifest.isManifestAsset(it) }.values.mapNotNull { asset ->
            if (asset.optLong("size", 0) > PatchManifest.MAX_MANIFEST_BYTES) return@mapNotNull null
            val text = fetchText(asset.optString("browser_download_url", ""), PatchManifest.MAX_MANIFEST_BYTES)
                ?: return@mapNotNull null
            val manifest = PatchManifest.parse(text)
                .onFailure { Timber.tag("OtaChecker").w("ignoring %s on %s: %s", asset.optString("name"), tag, it.message) }
                .getOrNull() ?: return@mapNotNull null
            val patchAsset = byName[manifest.assetName] ?: return@mapNotNull null
            if (patchAsset.optLong("size", -1) != manifest.size) {
                Timber.tag("OtaChecker").w("patch asset size differs from its manifest on %s", tag)
                return@mapNotNull null
            }
            PatchOffer(manifest, patchAsset.optString("browser_download_url", ""), tag)
        }
    }

    private fun fetchText(url: String, maxBytes: Int = 2 * 1024 * 1024): String? {
        if (!url.startsWith("https://")) return null
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes/${BuildConfig.VERSION_NAME}")
            .build()
        return runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = body.byteStream().use { it.readNBytesCompat(maxBytes + 1) }
                if (bytes.size > maxBytes) null else String(bytes, Charsets.UTF_8)
            }
        }.onFailure { Timber.tag("OtaChecker").w(it, "network error") }.getOrNull()
    }

    /** Pull the first `.apk` asset's direct download URL out of a release JSON (never a patch). */
    private fun firstApkAssetUrl(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true) && !isPatchAsset(name)) {
                return asset.optString("browser_download_url", "")
            }
        }
        return ""
    }

    companion object {
        const val PREFS = "ota_update"
        const val KEY_TEST_BUILDS = "test_builds"
    }
}

/** Patch archives are also `.apk` files; they must never be offered as the full installer. */
internal fun isPatchAsset(name: String): Boolean = name.startsWith("hermes-patch", ignoreCase = true)

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** The highest-versioned published release in a /releases listing, pre-releases included; drafts skipped. */
internal fun newestRelease(releases: JSONArray): JSONObject? {
    val items = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
    val index = newestIndex(items.map { it.optString("tag_name") }, items.map { it.optBoolean("draft") })
    return index?.let { items[it] }
}

/** Index of the highest version among [tags], skipping drafts and blank tags; null when there is none. */
internal fun newestIndex(tags: List<String>, drafts: List<Boolean>): Int? {
    var best: Int? = null
    tags.forEachIndexed { i, raw ->
        val tag = raw.removePrefix("v")
        if (drafts.getOrElse(i) { false } || tag.isBlank()) return@forEachIndexed
        if (best == null || isNewerVersion(tag, tags[best!!].removePrefix("v"))) best = i
    }
    return best
}

/**
 * True when [remote] is a later version than [current]. A build suffix is ignored:
 * "1.0.8-debug" used to read as 1.0.0, so a debug build was offered 1.0.4 as an update.
 */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    fun semver(v: String) = v.substringBefore('-').substringBefore('+')
        .split(".").map { it.toIntOrNull() ?: 0 }
    val r = semver(remote)
    val c = semver(current)
    for (i in 0 until maxOf(r.size, c.size)) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv > cv) return true
        if (rv < cv) return false
    }
    return false
}
