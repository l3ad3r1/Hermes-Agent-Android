package com.hermes.agent.data.server

import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.UUID

/**
 * Embedded OpenAI-compatible HTTP server — the Android port of
 * hermes-agent's `gateway/platforms/api_server.py`.
 *
 * Exposes the on-device agent so any OpenAI-compatible frontend (Open
 * WebUI, LobeChat, a script, another app on the phone) can use Hermes as a
 * backend:
 *   - `GET  /health`              → liveness
 *   - `GET  /v1/models`           → advertises the `hermes-agent` model
 *   - `POST /v1/chat/completions` → runs the agent (streaming + non-streaming)
 *
 * Requests are **stateless** (the Chat Completions contract): the messages
 * array IS the conversation. Each call runs the full [Orchestrator] (routing,
 * tools, skills, memory) under an ephemeral conversation id, so nothing is
 * written to the user's chat history.
 *
 * Auth: when an API key is configured, every request must present
 * `Authorization: Bearer <key>`. Transport/thread model is NanoHTTPD's
 * (one worker thread per request); suspend agent calls are bridged with
 * [runBlocking] for the non-streaming path and a piped stream + a coroutine
 * on [scope] for SSE.
 */
class HermesApiServer(
    hostname: String,
    port: Int,
    private val apiKey: String,
    private val orchestrator: Orchestrator,
    private val scope: CoroutineScope,
) : NanoHTTPD(hostname, port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" ->
                    json(Response.Status.OK, """{"status":"ok"}""")

                session.uri == "/v1/models" -> requireAuth(session) {
                    json(Response.Status.OK, ApiCompletion.modelsResponse())
                }

                session.method == Method.POST && session.uri == "/v1/chat/completions" ->
                    requireAuth(session) { handleCompletion(session) }

                else -> json(
                    Response.Status.NOT_FOUND,
                    ApiCompletion.errorResponse("no such endpoint: ${session.method} ${session.uri}", "not_found"),
                )
            }
        } catch (t: Throwable) {
            Timber.tag("ApiServer").e(t, "unhandled error serving %s", session.uri)
            json(
                Response.Status.INTERNAL_ERROR,
                ApiCompletion.errorResponse(t.message ?: "internal error", "server_error"),
            )
        }
    }

    private inline fun requireAuth(session: IHTTPSession, block: () -> Response): Response {
        val auth = session.headers["authorization"]
        return if (ApiCompletion.isAuthorized(apiKey, auth)) {
            block()
        } else {
            json(
                Response.Status.UNAUTHORIZED,
                ApiCompletion.errorResponse("missing or invalid Authorization bearer token", "authentication_error"),
            )
        }
    }

    private fun handleCompletion(session: IHTTPSession): Response {
        val body = readBody(session)
        val request = ApiCompletion.parseRequest(body)
            ?: return json(
                Response.Status.BAD_REQUEST,
                ApiCompletion.errorResponse("request must include a non-empty 'messages' array"),
            )
        val userMessage = request.lastUserMessage
            ?: return json(
                Response.Status.BAD_REQUEST,
                ApiCompletion.errorResponse("no user message found in 'messages'"),
            )

        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val conversationId = "api-" + UUID.randomUUID()
        val prior = request.priorContext()
        val promptTokens = ApiCompletion.estimateTokens(body)

        return if (request.stream) {
            streamingResponse(id, conversationId, userMessage, prior)
        } else {
            nonStreamingResponse(id, conversationId, userMessage, prior, promptTokens)
        }
    }

    private fun nonStreamingResponse(
        id: String,
        conversationId: String,
        userMessage: String,
        prior: List<com.hermes.agent.data.llm.LlmMessage>,
        promptTokens: Int,
    ): Response = runBlocking {
        val reply = StringBuilder()
        var failure: String? = null
        orchestrator.run(conversationId, userMessage, prior).collect { event ->
            when (event) {
                is OrchestratorEvent.ReplyToken -> reply.append(event.text)
                is OrchestratorEvent.ReplyComplete -> {
                    reply.setLength(0)
                    reply.append(event.finalText)
                }
                is OrchestratorEvent.Failed -> failure = event.message
                else -> { /* ignore plan/tool events for the API surface */ }
            }
        }
        if (failure != null) {
            json(
                Response.Status.INTERNAL_ERROR,
                ApiCompletion.errorResponse(failure ?: "agent failed", "server_error"),
            )
        } else {
            val text = reply.toString()
            json(
                Response.Status.OK,
                ApiCompletion.completionResponse(id, text, promptTokens, ApiCompletion.estimateTokens(text)),
            )
        }
    }

    private fun streamingResponse(
        id: String,
        conversationId: String,
        userMessage: String,
        prior: List<com.hermes.agent.data.llm.LlmMessage>,
    ): Response {
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)

        scope.launch {
            try {
                pipeOut.write(ApiCompletion.streamRoleChunk(id).toByteArray())
                pipeOut.flush()
                orchestrator.run(conversationId, userMessage, prior).collect { event ->
                    when (event) {
                        is OrchestratorEvent.ReplyToken -> {
                            pipeOut.write(ApiCompletion.streamChunk(id, event.text).toByteArray())
                            pipeOut.flush()
                        }
                        is OrchestratorEvent.Failed -> {
                            pipeOut.write(ApiCompletion.streamChunk(id, "\n[error] ${event.message}").toByteArray())
                            pipeOut.flush()
                        }
                        else -> { /* ignore non-text events */ }
                    }
                }
                pipeOut.write(ApiCompletion.streamDone(id).toByteArray())
                pipeOut.flush()
            } catch (t: Throwable) {
                Timber.tag("ApiServer").w(t, "streaming collect failed")
            } finally {
                runCatching { pipeOut.close() }
            }
        }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        // For application/json, NanoHTTPD stores the raw body under "postData".
        return map["postData"] ?: ""
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
}
