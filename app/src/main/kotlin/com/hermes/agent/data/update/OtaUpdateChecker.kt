package com.hermes.agent.data.update

import com.hermes.agent.BuildConfig
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
) {

    /**
     * Test builds: releases the self-repair pipeline publishes as pre-releases before they are
     * promoted to "latest". Off by default; a device that turns it on is the canary.
     */
    var testBuilds: Boolean
        get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEST_BUILDS, false)
        set(value) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEST_BUILDS, value).apply()

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val releaseNotes: String,
        /** Direct download URL of the .apk release asset, or "" if the release
         *  has no APK attached (then only the release page is available). */
        val apkUrl: String,
    )

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        // The single Hermes update channel (BuildConfig.UPDATE_REPO, from gradle.properties).
        // Blank means "no channel configured". Never fall back to the standalone
        // Hermes-Agent-Android or Octo-Jotter repos: their APKs carry a different
        // applicationId, so "updating" would install a SECOND app. See docs/UX_AUDIT.md JX-01.
        if (!BuildConfig.OTA_ENABLED || BuildConfig.UPDATE_REPO.isBlank()) {
            Timber.tag("OtaChecker").i("no update channel configured — skipping check")
            return@withContext null
        }
        val request = Request.Builder()
            .url(
                if (testBuilds) "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15"
                else "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest",
            )
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes/${BuildConfig.VERSION_NAME}")
            .build()

        val body = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        }.onFailure { Timber.tag("OtaChecker").w(it, "network error") }
            .getOrNull() ?: return@withContext null

        val obj = runCatching { if (testBuilds) newestRelease(JSONArray(body)) else JSONObject(body) }
            .onFailure { Timber.tag("OtaChecker").w(it, "JSON parse error") }
            .getOrNull() ?: return@withContext null

        val remoteVersion = obj.optString("tag_name", "").removePrefix("v")
        val releaseUrl = obj.optString("html_url", "")
        val releaseNotes = obj.optString("body", "").take(500)
        val apkUrl = firstApkAssetUrl(obj)

        Timber.tag("OtaChecker").d("remote=%s current=%s apk=%s", remoteVersion, BuildConfig.VERSION_NAME, apkUrl)

        if (remoteVersion.isBlank() || !isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
            return@withContext null
        }

        UpdateInfo(remoteVersion, releaseUrl, releaseNotes, apkUrl)
    }

    /** Pull the first `.apk` asset's direct download URL out of a release JSON. */
    private fun firstApkAssetUrl(release: JSONObject): String {
        val assets = release.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url", "")
            }
        }
        return ""
    }

    private fun isNewer(remote: String, current: String): Boolean = isNewerVersion(remote, current)

    companion object {
        const val PREFS = "ota_update"
        const val KEY_TEST_BUILDS = "test_builds"
    }
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
