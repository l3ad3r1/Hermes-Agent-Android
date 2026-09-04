package com.hermes.agent.data.server

import com.hermes.agent.domain.llm.LlmMessage
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.model.ChatStreamEvent
import com.hermes.agent.domain.model.Message
import com.hermes.agent.domain.model.MessageRole
import com.hermes.agent.domain.repository.ChatRepository
import com.hermes.agent.domain.repository.ConversationRepository
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * Transport-level integration test: starts the real [HermesApiServer] on a
 * free loopback port with a canned [Orchestrator] and drives it over HTTP.
 * Covers what the pure [ApiCompletionTest] can't — NanoHTTPD routing, body
 * parsing, and auth enforcement end-to-end.
 */
class HermesApiServerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: HermesApiServer? = null
    private var port: Int = 0

    private fun fakeOrchestrator(reply: String): Orchestrator = object : Orchestrator {
        override fun run(
            conversationId: String,
            userMessage: String,
            recentMessages: List<LlmMessage>,
            origin: ExecutionOrigin,
        ): Flow<OrchestratorEvent> = flow {
            emit(OrchestratorEvent.ReplyToken(reply))
            emit(OrchestratorEvent.ReplyComplete(reply, AgentRole.CONVERSATIONAL, isOnDevice = true))
        }
    }

    private fun start(apiKey: String = "", reply: String = "hi from hermes") {
        port = ServerSocket(0).use { it.localPort }
        server = HermesApiServer("127.0.0.1", port, apiKey, fakeOrchestrator(reply), scope).also {
            it.start(1000, false)
        }
    }

    /** Records the turns a persist call would write, and echoes a canned reply. */
    private class RecordingChatRepository(private val reply: String) : ChatRepository {
        val persisted = mutableListOf<Pair<String, String>>() // conversationId to userMessage

        override fun sendMessage(
            conversationId: String,
            content: String,
            attachmentUri: String?,
            attachmentMimeType: String?,
        ): Flow<ChatStreamEvent> = flow { }

        override fun sendMessageOrchestrated(
            conversationId: String,
            content: String,
            origin: ExecutionOrigin,
            attachmentUri: String?,
            attachmentMimeType: String?,
        ): Flow<OrchestratorEvent> = flow {
            persisted += conversationId to content
            emit(OrchestratorEvent.ReplyComplete(reply, AgentRole.CONVERSATIONAL, isOnDevice = true))
        }

        override fun summarizeConversation(conversationId: String) {}
    }

    private fun startWithPersist(reply: String): RecordingChatRepository {
        val chatRepo = RecordingChatRepository(reply)
        port = ServerSocket(0).use { it.localPort }
        server = HermesApiServer(
            "127.0.0.1", port, "", fakeOrchestrator("unused"), scope,
            chatRepository = chatRepo,
        ).also { it.start(1000, false) }
        return chatRepo
    }

    private fun startWithConversationRepo(): Pair<RecordingChatRepository, ConversationRepository> {
        val chatRepo = RecordingChatRepository("should not be used")
        val convRepo = mockk<ConversationRepository>(relaxed = true)
        port = ServerSocket(0).use { it.localPort }
        server = HermesApiServer(
            "127.0.0.1", port, "", fakeOrchestrator("unused"), scope,
            chatRepository = chatRepo,
            conversationRepository = convRepo,
        ).also { it.start(1000, false) }
        return chatRepo to convRepo
    }

    @Before fun noop() {}

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    private fun get(path: String, bearer: String? = null): okhttp3.Response {
        val b = Request.Builder().url("http://127.0.0.1:$port$path")
        if (bearer != null) b.addHeader("Authorization", "Bearer $bearer")
        return client.newCall(b.get().build()).execute()
    }

    private fun postCompletion(body: String, bearer: String? = null): okhttp3.Response {
        val b = Request.Builder()
            .url("http://127.0.0.1:$port/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (bearer != null) b.addHeader("Authorization", "Bearer $bearer")
        return client.newCall(b.build()).execute()
    }

    @Test
    fun `health endpoint returns ok`() {
        start()
        get("/health").use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("ok"))
        }
    }

    @Test
    fun `models endpoint lists hermes-agent`() {
        start()
        get("/v1/models").use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            assertEquals("hermes-agent", obj["data"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `non-streaming completion returns the agent reply`() {
        start(reply = "forty two")
        val body = """{"messages":[{"role":"user","content":"answer?"}]}"""
        postCompletion(body).use { resp ->
            assertEquals(200, resp.code)
            val obj = json.parseToJsonElement(resp.body!!.string()).jsonObject
            val content = obj["choices"]!!.jsonArray[0].jsonObject["message"]!!
                .jsonObject["content"]!!.jsonPrimitive.content
            assertEquals("forty two", content)
        }
    }

    @Test
    fun `missing messages returns 400`() {
        start()
        postCompletion("""{"model":"x"}""").use { resp ->
            assertEquals(400, resp.code)
        }
    }

    @Test
    fun `unauthorized without token when key configured`() {
        start(apiKey = "secret")
        postCompletion("""{"messages":[{"role":"user","content":"hi"}]}""").use { resp ->
            assertEquals(401, resp.code)
        }
    }

    @Test
    fun `authorized with correct token`() {
        start(apiKey = "secret", reply = "authed")
        postCompletion("""{"messages":[{"role":"user","content":"hi"}]}""", bearer = "secret").use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.body!!.string().contains("authed"))
        }
    }

    @Test
    fun `streaming completion emits SSE chunks and DONE`() {
        start(reply = "streamed")
        val body = """{"stream":true,"messages":[{"role":"user","content":"hi"}]}"""
        postCompletion(body).use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.header("Content-Type")!!.contains("text/event-stream"))
            val text = resp.body!!.string()
            assertTrue("expected content delta", text.contains("streamed"))
            assertTrue("expected DONE sentinel", text.contains("data: [DONE]"))
        }
    }

    @Test
    fun `persist request is routed through the chat repository`() {
        val chatRepo = startWithPersist(reply = "filed and answered")
        val body = """
            {"messages":[{"role":"user","content":"mail body"}],
             "hermes_persist_conversation":"po-your-note",
             "hermes_persist_title":"PO: your note"}
        """.trimIndent()
        postCompletion(body).use { resp ->
            assertEquals(200, resp.code)
            val content = json.parseToJsonElement(resp.body!!.string()).jsonObject["choices"]!!
                .jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            assertEquals("filed and answered", content)
        }
        assertEquals(listOf("po-your-note" to "mail body"), chatRepo.persisted)
    }

    @Test
    fun `deliver-only request files the mail without running the agent`() {
        val (chatRepo, convRepo) = startWithConversationRepo()
        val body = """
            {"messages":[{"role":"user","content":"fyi mail"}],
             "hermes_persist_conversation":"po-note",
             "hermes_persist_title":"PO: note",
             "hermes_deliver_only":true}
        """.trimIndent()
        postCompletion(body).use { resp ->
            assertEquals(200, resp.code)
        }
        // The agent was never invoked...
        assertTrue(chatRepo.persisted.isEmpty())
        // ...but the incoming mail was filed as a user message.
        val slot = slot<Message>()
        coVerify { convRepo.addMessage("po-note", capture(slot)) }
        assertEquals(MessageRole.USER, slot.captured.role)
        assertEquals("fyi mail", slot.captured.content)
    }

    @Test
    fun `unknown endpoint returns 404`() {
        start()
        get("/nope").use { resp ->
            assertEquals(404, resp.code)
        }
    }
}
