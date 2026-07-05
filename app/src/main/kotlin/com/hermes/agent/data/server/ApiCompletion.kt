package com.hermes.agent.data.server

import com.hermes.agent.data.llm.LlmMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure request/response codec for the OpenAI-compatible API server —
 * ported from hermes-agent's `.plans/openai-api-server.md`.
 *
 * Kept free of NanoHTTPD and Android so the parsing, auth decision, and
 * response shaping are unit-testable on a plain JVM. The [HermesApiServer]
 * is a thin transport that delegates here and to the orchestrator.
 */
object ApiCompletion {

    const val MODEL_ID = "hermes-agent"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Message(val role: String = "user", val content: String = "")

    /** Parsed view of a /v1/chat/completions request body. */
    data class Request(
        val messages: List<Message>,
        val stream: Boolean,
        val model: String,
    ) {
        /** The last user message drives the turn. */
        val lastUserMessage: String?
            get() = messages.lastOrNull { it.role == "user" }?.content?.takeIf { it.isNotBlank() }

        /** Everything before the final user turn becomes orchestrator context. */
        fun priorContext(): List<LlmMessage> {
            val lastUserIdx = messages.indexOfLast { it.role == "user" }
            if (lastUserIdx <= 0) return emptyList()
            return messages.subList(0, lastUserIdx).map { LlmMessage(role = it.role, content = it.content) }
        }
    }

    /**
     * Parse a request body. Returns null when the JSON is malformed or
     * carries no messages array (caller responds 400).
     */
    fun parseRequest(body: String): Request? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val messages = (root["messages"] as? JsonArray)?.map { el ->
            val obj = el.jsonObject
            Message(
                role = obj["role"]?.jsonPrimitive?.content ?: "user",
                content = contentToText(obj["content"]),
            )
        } ?: return null
        Request(
            messages = messages,
            stream = root["stream"]?.jsonPrimitive?.content?.toBoolean() ?: false,
            model = root["model"]?.jsonPrimitive?.content ?: MODEL_ID,
        )
    }.getOrNull()

    /** OpenAI allows string content OR an array of {type,text} parts. */
    private fun contentToText(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        null -> ""
        is JsonPrimitive -> el.content
        is JsonArray -> el.joinToString("") { part ->
            (part as? JsonObject)?.get("text")?.jsonPrimitive?.content.orEmpty()
        }
        else -> ""
    }

    /**
     * Authorization decision. When [configuredKey] is blank, no auth is
     * required. Otherwise the request must carry `Authorization: Bearer
     * <configuredKey>` (constant-time compared).
     */
    fun isAuthorized(configuredKey: String, authorizationHeader: String?): Boolean {
        if (configuredKey.isBlank()) return true
        // Require the standard `Bearer <token>` form — a bare token is rejected.
        val header = authorizationHeader ?: return false
        if (!header.startsWith("Bearer ")) return false
        val presented = header.removePrefix("Bearer ").trim()
        return constantTimeEquals(configuredKey, presented)
    }

    /** Non-streaming OpenAI chat.completion response JSON. */
    fun completionResponse(
        id: String,
        content: String,
        promptTokens: Int,
        completionTokens: Int,
        createdSeconds: Long = System.currentTimeMillis() / 1000,
    ): String = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("object", JsonPrimitive("chat.completion"))
        put("created", JsonPrimitive(createdSeconds))
        put("model", JsonPrimitive(MODEL_ID))
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("index", JsonPrimitive(0))
                put("message", buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive(content))
                })
                put("finish_reason", JsonPrimitive("stop"))
            })
        })
        put("usage", buildJsonObject {
            put("prompt_tokens", JsonPrimitive(promptTokens))
            put("completion_tokens", JsonPrimitive(completionTokens))
            put("total_tokens", JsonPrimitive(promptTokens + completionTokens))
        })
    }.toString()

    /** One SSE `data:` line for a streaming content delta. */
    fun streamChunk(id: String, delta: String, createdSeconds: Long = System.currentTimeMillis() / 1000): String {
        val obj = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(createdSeconds))
            put("model", JsonPrimitive(MODEL_ID))
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", JsonPrimitive(0))
                    put("delta", buildJsonObject { put("content", JsonPrimitive(delta)) })
                    put("finish_reason", kotlinx.serialization.json.JsonNull)
                })
            })
        }
        return "data: $obj\n\n"
    }

    /** The role-priming first chunk. */
    fun streamRoleChunk(id: String, createdSeconds: Long = System.currentTimeMillis() / 1000): String {
        val obj = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(createdSeconds))
            put("model", JsonPrimitive(MODEL_ID))
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", JsonPrimitive(0))
                    put("delta", buildJsonObject { put("role", JsonPrimitive("assistant")) })
                    put("finish_reason", kotlinx.serialization.json.JsonNull)
                })
            })
        }
        return "data: $obj\n\n"
    }

    /** The terminal stop chunk + `[DONE]` sentinel. */
    fun streamDone(id: String, createdSeconds: Long = System.currentTimeMillis() / 1000): String {
        val stop = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("object", JsonPrimitive("chat.completion.chunk"))
            put("created", JsonPrimitive(createdSeconds))
            put("model", JsonPrimitive(MODEL_ID))
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", JsonPrimitive(0))
                    put("delta", buildJsonObject { })
                    put("finish_reason", JsonPrimitive("stop"))
                })
            })
        }
        return "data: $stop\n\ndata: [DONE]\n\n"
    }

    /** GET /v1/models response. */
    fun modelsResponse(createdSeconds: Long = System.currentTimeMillis() / 1000): String = buildJsonObject {
        put("object", JsonPrimitive("list"))
        put("data", buildJsonArray {
            add(buildJsonObject {
                put("id", JsonPrimitive(MODEL_ID))
                put("object", JsonPrimitive("model"))
                put("created", JsonPrimitive(createdSeconds))
                put("owned_by", JsonPrimitive("hermes"))
            })
        })
    }.toString()

    fun errorResponse(message: String, type: String = "invalid_request_error"): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", JsonPrimitive(message))
            put("type", JsonPrimitive(type))
        })
    }.toString()

    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
