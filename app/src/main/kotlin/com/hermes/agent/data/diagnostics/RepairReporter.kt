package com.hermes.agent.data.diagnostics

import android.content.Context
import android.util.Base64
import com.hermes.agent.data.security.KeystoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files a problem report as an issue in the private self-repair repo.
 *
 * The report is redacted first ([ReportRedactor]) and the user has seen that text. With
 * [autoRepair] on, the issue gets the `repair` label, which starts the fix pipeline on the PC;
 * the fix still arrives as a draft PR for review, never as an install.
 *
 * The GitHub token is the user's own fine-grained token for that one repo, kept encrypted
 * with a Keystore key.
 */
@Singleton
class RepairReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: KeystoreManager,
) {
    private val prefs = context.getSharedPreferences("repair_reports", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    var autoRepair: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REPAIR, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REPAIR, value).apply()

    val isConfigured: Boolean get() = token().isNotBlank()

    fun setToken(token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }
        val blob = keystore.encrypt(ALIAS, clean.toByteArray())
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun token(): String = runCatching {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return ""
        String(keystore.decrypt(ALIAS, Base64.decode(stored, Base64.NO_WRAP)))
    }.getOrDefault("")

    /** Returns the issue URL. [body] must already be redacted and shown to the user. */
    suspend fun file(title: String, body: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = token().ifBlank { error("No GitHub token set for problem reports.") }
            val labels = buildList {
                add("from-${APP.lowercase()}")
                if (autoRepair) add("repair")
            }
            val payload = buildJsonObject {
                put("title", JsonPrimitive(title.take(120)))
                put("body", JsonPrimitive(body))
                put("labels", JsonArray(labels.map { JsonPrimitive(it) }))
            }.toString()
            val request = Request.Builder()
                .url("https://api.github.com/repos/$REPO/issues")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "GitHub said ${response.code}: ${text.take(200)}" }
                Json.parseToJsonElement(text).jsonObject["html_url"]?.jsonPrimitive?.content.orEmpty()
            }
        }
    }

    companion object {
        const val REPO = "l3ad3r1/jeeves-reports"

        /** Which app filed the report; the repair pipeline picks the code repo from it. */
        const val APP = "Hermes"
        private const val ALIAS = "jeeves.repair_reports_token"
        private const val KEY_TOKEN = "token"
        private const val KEY_AUTO_REPAIR = "auto_repair"

        /** The issue body in the shape of the repo's bug-report form. */
        fun body(component: String, what: String, logs: String, version: String): String = buildString {
            append("### App\n\n").append(APP).append("\n\n")
            append("### Component\n\n").append(component).append("\n\n")
            append("### What happened\n\n").append(what.trim()).append("\n\n")
            append("### Logs (redacted)\n\n```text\n").append(logs.trim().ifEmpty { "(none)" }).append("\n```\n\n")
            append("### App version\n\n").append(version).append('\n')
        }
    }
}
