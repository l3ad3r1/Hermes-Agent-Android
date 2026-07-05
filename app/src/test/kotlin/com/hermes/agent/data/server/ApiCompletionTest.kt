package com.hermes.agent.data.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCompletionTest {

    private val json = Json { ignoreUnknownKeys = true }

    // --- request parsing ---

    @Test
    fun `parses a standard chat completions request`() {
        val body = """
            {"model":"hermes-agent","stream":false,
             "messages":[
               {"role":"system","content":"be brief"},
               {"role":"user","content":"hello"}
             ]}
        """.trimIndent()
        val req = ApiCompletion.parseRequest(body)!!
        assertEquals("hermes-agent", req.model)
        assertFalse(req.stream)
        assertEquals("hello", req.lastUserMessage)
        // prior context = everything before the last user message.
        val prior = req.priorContext()
        assertEquals(1, prior.size)
        assertEquals("system", prior[0].role)
    }

    @Test
    fun `stream flag is parsed`() {
        val req = ApiCompletion.parseRequest("""{"stream":true,"messages":[{"role":"user","content":"hi"}]}""")!!
        assertTrue(req.stream)
    }

    @Test
    fun `handles array-style content parts`() {
        val body = """
            {"messages":[{"role":"user","content":[{"type":"text","text":"part one "},{"type":"text","text":"part two"}]}]}
        """.trimIndent()
        val req = ApiCompletion.parseRequest(body)!!
        assertEquals("part one part two", req.lastUserMessage)
    }

    @Test
    fun `returns null for malformed json`() {
        assertNull(ApiCompletion.parseRequest("{not json"))
    }

    @Test
    fun `returns null when messages array is absent`() {
        assertNull(ApiCompletion.parseRequest("""{"model":"x"}"""))
    }

    @Test
    fun `lastUserMessage is null when there is no user turn`() {
        val req = ApiCompletion.parseRequest("""{"messages":[{"role":"system","content":"hi"}]}""")!!
        assertNull(req.lastUserMessage)
    }

    // --- auth ---

    @Test
    fun `no configured key allows any request`() {
        assertTrue(ApiCompletion.isAuthorized("", null))
        assertTrue(ApiCompletion.isAuthorized("", "Bearer whatever"))
    }

    @Test
    fun `configured key requires a matching bearer token`() {
        assertTrue(ApiCompletion.isAuthorized("secret", "Bearer secret"))
        assertFalse(ApiCompletion.isAuthorized("secret", "Bearer wrong"))
        assertFalse(ApiCompletion.isAuthorized("secret", null))
        assertFalse(ApiCompletion.isAuthorized("secret", "secret")) // missing Bearer prefix
    }

    // --- response shaping ---

    @Test
    fun `completion response is openai-shaped`() {
        val out = ApiCompletion.completionResponse("chatcmpl-1", "the answer", 10, 3, createdSeconds = 1700000000)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("chat.completion", obj["object"]!!.jsonPrimitive.content)
        assertEquals("hermes-agent", obj["model"]!!.jsonPrimitive.content)
        val choice = obj["choices"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", choice["message"]!!.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("the answer", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals(13, obj["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `stream chunk is a single SSE data line`() {
        val chunk = ApiCompletion.streamChunk("id1", "Hello", createdSeconds = 1700000000)
        assertTrue(chunk.startsWith("data: "))
        assertTrue(chunk.endsWith("\n\n"))
        val payload = chunk.removePrefix("data: ").trim()
        val obj = json.parseToJsonElement(payload).jsonObject
        assertEquals("chat.completion.chunk", obj["object"]!!.jsonPrimitive.content)
        assertEquals(
            "Hello",
            obj["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `stream done emits stop chunk and DONE sentinel`() {
        val done = ApiCompletion.streamDone("id1", createdSeconds = 1700000000)
        assertTrue(done.contains("\"finish_reason\":\"stop\""))
        assertTrue(done.trimEnd().endsWith("data: [DONE]"))
    }

    @Test
    fun `models response advertises hermes-agent`() {
        val out = ApiCompletion.modelsResponse(createdSeconds = 1700000000)
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("list", obj["object"]!!.jsonPrimitive.content)
        assertEquals("hermes-agent", obj["data"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `error response carries a message and type`() {
        val out = ApiCompletion.errorResponse("bad request", "invalid_request_error")
        val err = json.parseToJsonElement(out).jsonObject["error"]!!.jsonObject
        assertEquals("bad request", err["message"]!!.jsonPrimitive.content)
        assertEquals("invalid_request_error", err["type"]!!.jsonPrimitive.content)
    }
}
