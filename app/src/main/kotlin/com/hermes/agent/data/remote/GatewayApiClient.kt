package com.hermes.agent.data.remote

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.util.DispatcherProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * HTTP + SSE client for the PC Hermes gateway's REST API.
 *
 * Wraps the Runs API (for [RemoteOrchestrator]) and the Sessions API (for
 * [RemoteConversationRepository]). All calls send `Authorization: Bearer
 * <key>` and read the base URL + key from [SettingsRepository] at call time
 * so settings changes take effect without a restart of this client.
 *
 * The gateway's API surface is documented in the NousResearch/hermes-agent
 * `gateway/platforms/api_server.py` module:
 *
 *   - `POST /v1/runs` — start a run, returns `run_id` (202)
 *   - `GET  /v1/runs/{id}/events` — SSE stream of lifecycle events
 *   - `POST /v1/runs/{id}/approval` — resolve a pending approval
 *   - `POST /v1/runs/{id}/stop` — interrupt a running agent
 *   - `GET  /api/sessions` — list sessions
 *   - `POST /api/sessions` — create a session
 *   - `GET  /api/sessions/{id}` — read session metadata
 *   - `PATCH /api/sessions/{id}` — update title
 *   - `DELETE /api/sessions/{id}` — delete a session
 *   - `GET  /api/sessions/{id}/messages` — message history
 *   - `POST /api/sessions/{id}/fork` — branch a session
 */
@Singleton
class GatewayApiClient @Inject constructor(
    private val rawClient: OkHttpClient,
    private val json: Json,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val tailnet: TailnetNode,
) {

    private val jsonMediaType = "application/json".toMediaType()

    /** The HTTP client, routed through the embedded tailnet node while that node is running. */
    private val client: OkHttpClient get() = tailnet.wrap(rawClient)

    private suspend fun baseUrl(): String =
        settingsRepository.current().remoteGatewayUrl.trimEnd('/')

    private suspend fun apiKey(): String =
        settingsRepository.current().remoteGatewayApiKey

    private fun authBuilder(url: String, key: String): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $key")

    // ── Runs API ──────────────────────────────────────────────────────────

    /**
     * Start a new agent run on the PC gateway. Returns the `run_id`.
     *
     * When [sessionId] is provided, the gateway loads that session's active
     * transcript so the run has full conversation context — this is what
     * enables seamless handoff (the PC is the canonical store).
     *
     * [instructions] is applied by the gateway as an ephemeral system prompt for this run only:
     * it is added to the agent's own and never saved, so it shapes a run without editing the PC.
     */
    suspend fun startRun(
        input: String,
        sessionId: String?,
        profile: String? = null,
        instructions: String? = null,
    ): String = withContext(dispatchers.io) {
        val base = baseUrl() + profilePrefix(profile)
        val key = apiKey()
        val body = buildString {
            append("{")
            append("\"input\":").append(json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(input)))
            if (sessionId != null) {
                append(",\"session_id\":").append(json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(sessionId)))
            }
            if (!instructions.isNullOrBlank()) {
                append(",\"instructions\":").append(json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(instructions)))
            }
            append("}")
        }
        val request = authBuilder("$base/v1/runs", key)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string().orEmpty()
            response.close()
            throw IOException("startRun failed: ${response.code} $errBody")
        }
        val responseBody = response.body?.string().orEmpty()
        response.close()
        val parsed = json.parseToJsonElement(responseBody).jsonObject
        parsed["run_id"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("startRun: no run_id in response")
    }

    /**
     * Stream lifecycle events for a run as a Kotlin [Flow].
     *
     * Cancelling the flow cancels the OkHttp call. Each `data: {...}` SSE
     * line is parsed into a [GatewayEvent]. The flow completes when the
     * stream ends (run completed/failed/cancelled) or the connection drops.
     */
    fun streamRunEvents(runId: String, profile: String? = null): Flow<GatewayEvent> = flow {
        val base = baseUrl() + profilePrefix(profile)
        val key = apiKey()
        val request = authBuilder("$base/v1/runs/$runId/events", key).build()
        // execute() throws on a dropped or timed-out connection; left uncaught that escaped the
        // flow and crashed the app, so report it like any other lost stream.
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            Timber.tag("GatewayClient").w(e, "SSE connect failed")
            emit(GatewayEvent.RunFailed("Connection lost: ${e.message ?: "could not open the event stream"}"))
            return@flow
        }
        if (!response.isSuccessful) {
            response.close()
            emit(GatewayEvent.RunFailed("events stream failed: ${response.code}"))
            return@flow
        }
        val body = response.body ?: run {
            response.close()
            emit(GatewayEvent.RunFailed("events stream: empty body"))
            return@flow
        }
        val reader = BufferedReader(body.charStream())
        try {
            while (coroutineContext[Job]?.isActive != false) {
                val line = reader.readLine() ?: break
                when {
                    // SSE comment — ": stream closed" etc. — signals end.
                    line.startsWith(":") && line.contains("closed", ignoreCase = true) -> break
                    line.startsWith("event:") -> { /* gateway puts event type in JSON, not here */ }
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (data.isNotBlank() && data != "[DONE]") {
                            parseEvent("", data)?.let { emit(it) }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (coroutineContext[Job]?.isActive != false) {
                Timber.tag("GatewayClient").w(e, "SSE stream interrupted")
                // Surface the drop so the UI leaves the loading state instead
                // of hanging on isSending = true until the user taps Stop.
                emit(GatewayEvent.RunFailed("Connection lost: ${e.message ?: "SSE stream interrupted"}"))
            }
        } finally {
            response.close()
        }
    }.flowOn(dispatchers.io)

    /**
     * Resolve a pending approval for a run. A run belongs to the profile that started it, so an
     * approval for any bot but the default has to go to that profile's own endpoint.
     */
    suspend fun submitApproval(
        runId: String,
        approved: Boolean,
        requestId: String = "",
        profile: String? = null,
    ) = withContext(dispatchers.io) {
        val base = baseUrl() + profilePrefix(profile)
        val key = apiKey()
        // The gateway expects {"choice": "once"|"deny"}; a bare
        // {"approved": <bool>} body is rejected with `invalid_approval_choice`
        // (400) and the run keeps waiting for a decision on the PC.
        // Room-scoped or profile-scoped gateway instances additionally
        // require the approval's request_id and reject requests without it.
        val choice = if (approved) "once" else "deny"
        val body = buildJsonObject {
            put("choice", JsonPrimitive(choice))
            if (requestId.isNotBlank()) put("request_id", JsonPrimitive(requestId))
        }.toString()
        val request = authBuilder("$base/v1/runs/$runId/approval", key)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val errorBody = if (response.isSuccessful) "" else response.body?.string().orEmpty()
        response.close()
        if (!response.isSuccessful) {
            Timber.tag("GatewayClient").w("submitApproval failed: %d %s", response.code, errorBody.take(200))
        }
    }

    /** Interrupt a running agent. */
    suspend fun stopRun(runId: String, profile: String? = null) = withContext(dispatchers.io) {
        val base = baseUrl() + profilePrefix(profile)
        val key = apiKey()
        val request = authBuilder("$base/v1/runs/$runId/stop", key)
            .post("".toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        response.close()
    }

    // ── Sessions API ──────────────────────────────────────────────────────

    /** List all sessions on the PC gateway. */
    suspend fun listSessions(): List<RemoteSession> = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val request = authBuilder("$base/api/sessions", key).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("listSessions failed: ${response.code}")
        }
        val body = response.body?.string().orEmpty()
        response.close()
        parseSessions(body)
    }

    /** Create a new empty session on the PC gateway. */
    suspend fun createSession(title: String): RemoteSession = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val body = """{"title":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(title))}}"""
        val request = authBuilder("$base/api/sessions", key)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("createSession failed: ${response.code}")
        }
        val responseBody = response.body?.string().orEmpty()
        response.close()
        parseSession(responseBody)
    }

    /** Read a session's metadata. */
    suspend fun getSession(id: String): RemoteSession = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val request = authBuilder("$base/api/sessions/$id", key).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("getSession failed: ${response.code}")
        }
        val body = response.body?.string().orEmpty()
        response.close()
        parseSession(body)
    }

    /** Update a session's title. */
    suspend fun updateSession(id: String, title: String) = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val body = """{"title":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(title))}}"""
        val request = authBuilder("$base/api/sessions/$id", key)
            .patch(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        response.close()
    }

    /** Delete a session. */
    suspend fun deleteSession(id: String) = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val request = authBuilder("$base/api/sessions/$id", key).delete().build()
        val response = client.newCall(request).execute()
        response.close()
    }

    /** Read a session's message history. */
    suspend fun getSessionMessages(id: String): List<RemoteMessage> = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val request = authBuilder("$base/api/sessions/$id/messages", key).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("getSessionMessages failed: ${response.code}")
        }
        val body = response.body?.string().orEmpty()
        response.close()
        parseMessages(body)
    }

    /** Fork a session into a new branched session. */
    suspend fun forkSession(id: String, title: String): RemoteSession = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val body = """{"title":${json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(title))}}"""
        val request = authBuilder("$base/api/sessions/$id/fork", key)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("forkSession failed: ${response.code}")
        }
        val responseBody = response.body?.string().orEmpty()
        response.close()
        parseSession(responseBody)
    }

    // ── Health check ───────────────────────────────────────────────────────

    /** Ping the gateway's health endpoint. Returns true if reachable. */
    suspend fun healthCheck(): Boolean = withContext(dispatchers.io) {
        val base = baseUrl()
        val key = apiKey()
        val request = authBuilder("$base/health", key).build()
        val response = client.newCall(request).execute()
        val ok = response.isSuccessful
        response.close()
        ok
    }

    // ── Jobs API (the PC's cron bots) ─────────────────────────────────────

    /**
     * List a profile's scheduled jobs, paused ones included so they can be resumed. A named
     * [profile] is reached through a multiplexing gateway's `/p/<profile>/` prefix; null or
     * "default" uses the unprefixed routes.
     */
    suspend fun listJobs(profile: String? = null): List<RemoteJob> = withContext(dispatchers.io) {
        val request = authBuilder("${baseUrl()}${profilePrefix(profile)}/api/jobs?include_disabled=true", apiKey()).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        response.close()
        if (!response.isSuccessful) throw IOException("listJobs failed: ${response.code} ${body.take(200)}")
        parseJobs(body)
    }

    /** Apply `pause`, `resume` or `run` to a job and return its updated state. */
    suspend fun jobAction(jobId: String, action: String, profile: String? = null): RemoteJob = withContext(dispatchers.io) {
        require(action in JOB_ACTIONS) { "unknown job action: $action" }
        val request = authBuilder("${baseUrl()}${profilePrefix(profile)}/api/jobs/$jobId/$action", apiKey())
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        response.close()
        if (!response.isSuccessful) throw IOException("$action job failed: ${response.code} ${body.take(200)}")
        val obj = json.parseToJsonElement(body).jsonObject
        parseJob((obj["job"] as? JsonObject) ?: obj)
            ?: throw IOException("$action job: could not parse response: ${body.take(200)}")
    }

    /**
     * The bots this gateway serves, from its `/api/profiles`, or null when it has no such
     * endpoint (an older gateway) — the caller then has to ask by name with [profileExists].
     */
    suspend fun listProfiles(): List<RemoteProfile>? = withContext(dispatchers.io) {
        val request = authBuilder("${baseUrl()}/api/profiles", apiKey()).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        response.close()
        if (response.code == 404) return@withContext null
        if (!response.isSuccessful) throw IOException("listProfiles failed: ${response.code} ${body.take(200)}")
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return@withContext emptyList()
        data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            str("name").takeIf { it.isNotBlank() }
                ?.let { RemoteProfile(it, str("display_name"), str("description")) }
        }
    }

    /** Whether the gateway answers under `/p/<profile>/`, i.e. serves a bot of that name. */
    suspend fun profileExists(profile: String): Boolean = withContext(dispatchers.io) {
        val request = authBuilder("${baseUrl()}${profilePrefix(profile)}/health", apiKey()).build()
        val response = client.newCall(request).execute()
        val ok = response.isSuccessful
        response.close()
        ok
    }

    private fun profilePrefix(profile: String?): String =
        if (profile.isNullOrBlank() || profile == "default") "" else "/p/$profile"

    /**
     * Fetch every one of [profiles]' jobs concurrently, pairing each with its own [Result] so
     * one profile being unreachable doesn't block or blank out the others — used by both the
     * Bots dashboard and the CRON screen's PC-bots roundup.
     */
    suspend fun listJobsByProfile(profiles: List<String>): List<Pair<String, Result<List<RemoteJob>>>> = coroutineScope {
        profiles.map { profile ->
            async { profile to runCatching { listJobs(profile.takeIf { it != BotProfileStore.DEFAULT }) } }
        }.awaitAll()
    }

    // ── Reddit approvals API (anshkosh-reddit plugin) ──────────────────────
    // The plugin's draft queue is shared, PC-wide state, always reached through the
    // default profile — same as the Telegram bot that owns it today.

    /** List drafts waiting for approval, with their critique and (if any) the critic's rewrite. */
    suspend fun listRedditDrafts(): List<RedditDraft> = withContext(dispatchers.io) {
        val request = authBuilder("${baseUrl()}/api/reddit/drafts", apiKey()).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        response.close()
        if (!response.isSuccessful) throw IOException("listRedditDrafts failed: ${response.code} ${body.take(200)}")
        parseRedditDrafts(body)
    }

    /** Post [code] (or its critic rewrite, if [useRewrite]) in the PC's logged-in Chrome window. No LLM in between. */
    suspend fun approveRedditDraft(code: String, useRewrite: Boolean): String = withContext(dispatchers.io) {
        val body = buildJsonObject { put("rewrite", JsonPrimitive(useRewrite)) }.toString()
        val request = authBuilder("${baseUrl()}/api/reddit/drafts/$code/approve", apiKey())
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        response.close()
        if (!response.isSuccessful) throw IOException("approve $code failed: ${response.code} ${responseBody.take(300)}")
        redditResultText(responseBody)
    }

    /** Drop a pending draft without posting it. */
    suspend fun skipRedditDraft(code: String): String = withContext(dispatchers.io) {
        val request = authBuilder("${baseUrl()}/api/reddit/drafts/$code/skip", apiKey())
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        response.close()
        if (!response.isSuccessful) throw IOException("skip $code failed: ${response.code} ${responseBody.take(300)}")
        redditResultText(responseBody)
    }

    private fun redditResultText(body: String): String =
        (json.parseToJsonElement(body).jsonObject["result"] as? JsonPrimitive)?.contentOrNull ?: body

    private fun parseRedditDrafts(body: String): List<RedditDraft> {
        val drafts = runCatching { json.parseToJsonElement(body).jsonObject["drafts"] }.getOrNull()
            as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return drafts.mapNotNull { (it as? JsonObject)?.let(::parseRedditDraft) }
    }

    private fun parseRedditDraft(obj: JsonObject): RedditDraft? {
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        return RedditDraft(
            code = str("code") ?: return null,
            sub = str("sub") ?: "",
            title = str("title") ?: "",
            url = str("url") ?: "",
            text = str("text") ?: "",
            critic = str("critic"),
            score = str("score")?.toIntOrNull(),
            issues = str("issues"),
            rewrite = str("rewrite"),
        )
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    private fun parseJobs(body: String): List<RemoteJob> {
        val jobs = runCatching { json.parseToJsonElement(body).jsonObject["jobs"] }.getOrNull()
            as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return jobs.mapNotNull { (it as? JsonObject)?.let(::parseJob) }
    }

    private fun parseJob(obj: JsonObject): RemoteJob? {
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        return RemoteJob(
            id = str("id") ?: return null,
            name = str("name") ?: "",
            schedule = str("schedule_display") ?: "",
            enabled = str("enabled")?.toBooleanStrictOrNull() ?: true,
            state = str("state") ?: "",
            nextRunAt = str("next_run_at"),
            lastRunAt = str("last_run_at"),
            lastStatus = str("last_status"),
            lastError = str("last_error"),
        )
    }

    private fun parseEvent(type: String, data: String): GatewayEvent? {
        if (data.isBlank() || data == "[DONE]") return null
        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrElse {
            Timber.tag("GatewayClient").w(it, "could not parse SSE event: %s", data.take(200))
            return null
        }
        // The gateway puts the event type inside the JSON as "event",
        // not as a separate SSE event: line. Fall back to the SSE type
        // parameter for compatibility with standard SSE senders.
        val eventType = obj["event"]?.jsonPrimitive?.contentOrNull ?: type
        return when (eventType) {
            "message.delta", "assistant.delta" -> {
                val text = obj["delta"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                GatewayEvent.MessageDelta(text)
            }
            "message.complete", "assistant.complete" -> {
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: obj["output"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                GatewayEvent.MessageComplete(text)
            }
            "tool.started", "tool.start" -> {
                GatewayEvent.ToolStarted(
                    callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["tool"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = obj["arguments"]?.jsonPrimitive?.contentOrNull
                        ?: obj["preview"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            "tool.completed", "tool.complete" -> {
                GatewayEvent.ToolCompleted(
                    callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = obj["tool"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    output = obj["output"]?.jsonPrimitive?.contentOrNull
                        ?: obj["result"]?.jsonPrimitive?.contentOrNull ?: "",
                    success = obj["error"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                        ?.let { !it } ?: true,
                )
            }
            "approval.request" -> {
                // The gateway's approval payload carries `description` (a human
                // label for the action) and the redacted `command` - not the
                // tool/name/arguments shape used by tool.* events. Map them so
                // the confirmation dialog shows what is actually being approved.
                val command = obj["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                GatewayEvent.ApprovalRequested(
                    callId = obj["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["request_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    toolName = description.ifBlank { command },
                    arguments = if (command.isBlank()) {
                        ""
                    } else {
                        buildJsonObject { put("command", JsonPrimitive(command)) }.toString()
                    },
                    requestId = obj["request_id"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            "run.completed" -> {
                GatewayEvent.RunCompleted(
                    obj["output"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            "run.failed" -> {
                GatewayEvent.RunFailed(
                    obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: obj["error"]?.jsonPrimitive?.contentOrNull ?: "run failed",
                )
            }
            "subagent.start" -> {
                GatewayEvent.SubagentStarted(
                    childSessionId = obj["child_session_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    delegationId = obj["delegation_id"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            "subagent.complete" -> {
                GatewayEvent.SubagentCompleted(
                    childSessionId = obj["child_session_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "",
                    summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }
            // Informational events we don't surface to the UI yet.
            "reasoning.available", "reasoning.delta", "reasoning.complete" -> null
            else -> GatewayEvent.Unknown(eventType, data)
        }
    }

    private fun parseSessions(body: String): List<RemoteSession> {
        val arr = runCatching { json.parseToJsonElement(body) }.getOrElse { return emptyList() }
        return when {
            arr is kotlinx.serialization.json.JsonArray -> arr.mapNotNull { parseSession(it.jsonObject) }
            arr is JsonObject && arr["sessions"] is kotlinx.serialization.json.JsonArray ->
                arr["sessions"]!!.let { it as kotlinx.serialization.json.JsonArray }.mapNotNull { el -> parseSession(el.jsonObject) }
            arr is JsonObject && arr["data"] is kotlinx.serialization.json.JsonArray ->
                arr["data"]!!.let { it as kotlinx.serialization.json.JsonArray }.mapNotNull { el -> parseSession(el.jsonObject) }
            else -> emptyList()
        }
    }

    private fun parseSession(body: String): RemoteSession =
        parseSession(json.parseToJsonElement(body).jsonObject)
            ?: throw IOException("could not parse session: ${body.take(200)}")

    private fun parseSession(obj: JsonObject): RemoteSession? {
        // The gateway wraps session objects in a "session" key for single
        // responses (POST /api/sessions, GET /api/sessions/{id}) but not for
        // list responses (GET /api/sessions wraps in "data" array).
        val sessionObj = (obj["session"] as? JsonObject) ?: obj
        val id = sessionObj["id"]?.jsonPrimitive?.contentOrNull
            ?: sessionObj["session_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return RemoteSession(
            id = id,
            title = sessionObj["title"]?.jsonPrimitive?.contentOrNull ?: "Untitled",
            createdAt = ((sessionObj["started_at"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: sessionObj["created_at"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: sessionObj["created"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: (System.currentTimeMillis() / 1000.0)) * 1000).toLong(),
            updatedAt = ((sessionObj["last_active"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: sessionObj["updated_at"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: sessionObj["updated"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: (System.currentTimeMillis() / 1000.0)) * 1000).toLong(),
            messageCount = sessionObj["message_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }

    private fun parseMessages(body: String): List<RemoteMessage> {
        val arr = runCatching { json.parseToJsonElement(body) }.getOrElse { return emptyList() }
        val elements = when {
            arr is kotlinx.serialization.json.JsonArray -> arr
            arr is JsonObject && arr["messages"] is kotlinx.serialization.json.JsonArray -> arr["messages"] as kotlinx.serialization.json.JsonArray
            arr is JsonObject && arr["data"] is kotlinx.serialization.json.JsonArray -> arr["data"] as kotlinx.serialization.json.JsonArray
            else -> return emptyList()
        }
        return elements.mapNotNull { el -> parseMessage(el.jsonObject) }
    }

    private fun parseMessage(obj: JsonObject): RemoteMessage? {
        val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
        return RemoteMessage(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: java.util.UUID.randomUUID().toString(),
            role = role,
            content = content,
            timestamp = ((obj["timestamp"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: obj["created_at"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                ?: (System.currentTimeMillis() / 1000.0)) * 1000).toLong(),
        )
    }
}

/** A session on the PC gateway, mapped from the `/api/sessions` response. */
/** A bot the gateway serves: its profile name, and the display name and description it was given. */
data class RemoteProfile(val name: String, val displayName: String = "", val description: String = "")

data class RemoteSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
)

/** Actions accepted by `POST /api/jobs/{id}/{action}`. */
val JOB_ACTIONS = setOf("pause", "resume", "run")

/** A scheduled job (bot) on the PC gateway, mapped from `/api/jobs`. Timestamps are ISO-8601. */
data class RemoteJob(
    val id: String,
    val name: String,
    val schedule: String,
    val enabled: Boolean,
    val state: String,
    val nextRunAt: String?,
    val lastRunAt: String?,
    val lastStatus: String?,
    val lastError: String?,
)

/**
 * A u/Anshkoshmod Reddit comment draft waiting for approval, mapped from `/api/reddit/drafts`.
 * [critic]/[score]/[issues]/[rewrite] are null until the desktop's critique cron has run.
 */
data class RedditDraft(
    val code: String,
    val sub: String,
    val title: String,
    val url: String,
    val text: String,
    val critic: String?,
    val score: Int?,
    val issues: String?,
    val rewrite: String?,
)

/** A message in a PC gateway session, mapped from `/api/sessions/{id}/messages`. */
data class RemoteMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
)
