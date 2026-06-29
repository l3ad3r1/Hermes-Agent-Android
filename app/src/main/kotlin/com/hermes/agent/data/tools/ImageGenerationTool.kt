package com.hermes.agent.data.tools

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generate an image from a text prompt. Ported from hermes-agent's
 * `image_generation_tool.py`.
 *
 * Upstream supports many providers; here we call the OpenAI-compatible
 * `/images/generations` endpoint using the same cloud base URL and API key the
 * app already uses for chat (Settings → Cloud). That covers OpenAI and any
 * compatible gateway (OpenRouter, Nous Portal, a self-hosted proxy, …) without
 * extra configuration. The tool returns the generated image URL(s) for the
 * agent to surface to the user.
 */
@Singleton
class ImageGenerationTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val settings: SettingsRepository,
    private val json: Json,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "generate_image",
        description = "Generate an image from a text description and return its URL. Use this when " +
            "the user asks you to create, draw, illustrate, or imagine a picture. Write a vivid, " +
            "detailed prompt. Requires cloud access to be configured (Settings → Cloud).",
        parameters = listOf(
            ToolParameter(
                name = "prompt",
                type = ToolParameterType.STRING,
                description = "A detailed description of the image to generate.",
            ),
            ToolParameter(
                name = "size",
                type = ToolParameterType.STRING,
                description = "Image size, e.g. '1024x1024' (default), '1792x1024', '1024x1792'.",
                required = false,
            ),
            ToolParameter(
                name = "model",
                type = ToolParameterType.STRING,
                description = "Image model id to use. Defaults to 'dall-e-3'. Set this if your " +
                    "provider uses a different image model.",
                required = false,
            ),
        ),
        category = "information",
        requiresEnv = listOf("cloudApiKey"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val prompt = (arguments["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (prompt.isNullOrEmpty()) {
            return ToolResult.error("missing required parameter: prompt", System.currentTimeMillis() - start)
        }
        val size = (arguments["size"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: "1024x1024"
        val model = (arguments["model"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: "dall-e-3"

        val s = settings.current()
        if (!s.cloudEnabled || s.cloudApiKey.isBlank()) {
            return ToolResult.error(
                "Cloud access isn't configured. Enable cloud and add an API key in Settings.",
                System.currentTimeMillis() - start,
            )
        }

        val endpoint = s.cloudBaseUrl.trimEnd('/') + "/images/generations"
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${s.cloudApiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        return try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val reason = when (response.code) {
                            404 -> "This provider has no image-generation endpoint at $endpoint. " +
                                "The configured cloud provider/model does not support image generation. " +
                                "Tell the user plainly that their provider can't generate images and they'd " +
                                "need an image-capable endpoint (e.g. OpenAI). Do NOT invent app settings or " +
                                "menus — this app has no image-generation settings screen."
                            401, 403 -> "Authentication failed (HTTP ${response.code}). The cloud API key " +
                                "may be invalid or lack image permissions."
                            else -> "Image API HTTP ${response.code}: ${raw.take(200)}"
                        }
                        return@use ToolResult.error(reason, System.currentTimeMillis() - start)
                    }
                    parseUrls(raw)?.let { urls ->
                        ToolResult.ok(
                            "Generated ${urls.size} image(s) for: \"$prompt\"\n" + urls.joinToString("\n"),
                            System.currentTimeMillis() - start,
                        )
                    } ?: ToolResult.error(
                        "Image generated but the response had no URL (the provider may return base64 " +
                            "only, which isn't supported here).",
                        System.currentTimeMillis() - start,
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "image generation request failed", System.currentTimeMillis() - start)
        }
    }

    /** Pull `data[].url` from an OpenAI-compatible images response. */
    private fun parseUrls(raw: String): List<String>? {
        val data = runCatching {
            json.parseToJsonElement(raw).jsonObject["data"]?.jsonArray
        }.getOrNull() ?: return null
        val urls = data.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null } }
        return urls.ifEmpty { null }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
