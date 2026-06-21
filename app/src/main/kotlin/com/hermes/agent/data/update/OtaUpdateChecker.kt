package com.hermes.agent.data.update

import com.hermes.agent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OtaUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    data class UpdateInfo(
        val version: String,
        val releaseUrl: String,
        val releaseNotes: String,
    )

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/l3ad3r1/Hermes-Agent-Android/releases/latest")
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Hermes-Agent-Android/${BuildConfig.VERSION_NAME}")
            .build()

        val body = runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        }.onFailure { Timber.tag("OtaChecker").w(it, "network error") }
            .getOrNull() ?: return@withContext null

        val obj = runCatching { JSONObject(body) }
            .onFailure { Timber.tag("OtaChecker").w(it, "JSON parse error") }
            .getOrNull() ?: return@withContext null

        val remoteVersion = obj.optString("tag_name", "").removePrefix("v")
        val releaseUrl = obj.optString("html_url", "")
        val releaseNotes = obj.optString("body", "").take(500)

        Timber.tag("OtaChecker").d("remote=%s current=%s", remoteVersion, BuildConfig.VERSION_NAME)

        if (remoteVersion.isBlank() || !isNewer(remoteVersion, BuildConfig.VERSION_NAME)) {
            return@withContext null
        }

        UpdateInfo(remoteVersion, releaseUrl, releaseNotes)
    }

    private fun isNewer(remote: String, current: String): Boolean {
        fun semver(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
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
}
