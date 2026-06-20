package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real web search via DuckDuckGo Instant Answer API.
 * No API key required. Returns abstract text + related topics.
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "web_search",
        description = "Search the web for current information. Returns a summary and related results.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "The search query.",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Max related results to return (default 5).",
                required = false,
            ),
        ),
        category = "information",
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: query")
        val limit = (arguments["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 5

        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"

            val request = Request.Builder().url(url)
                .header("User-Agent", "HermesAgentAndroid/1.0")
                .build()

            val responseBody = okHttpClient.newCall(request).execute().use { it.body?.string() }
                ?: return ToolResult.error("Empty response from search API")

            val root = json.parseToJsonElement(responseBody).jsonObject
            val results = mutableListOf<Map<String, String>>()

            // Top direct answer (abstract)
            val abstractText = root["AbstractText"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val abstractUrl  = root["AbstractURL"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val abstractSrc  = root["AbstractSource"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (abstractText.isNotBlank()) {
                results += mapOf("title" to abstractSrc, "url" to abstractUrl, "snippet" to abstractText)
            }

            // Related topics
            root["RelatedTopics"]?.jsonArray?.take(limit)?.forEach { el ->
                runCatching {
                    val obj = el.jsonObject
                    val text = obj["Text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val link = obj["FirstURL"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (text.isNotBlank()) {
                        results += mapOf("title" to text.take(60), "url" to link, "snippet" to text)
                    }
                }
            }

            if (results.isEmpty()) {
                return ToolResult.ok("No results found for \"$query\".", System.currentTimeMillis() - start)
            }

            val output = results.joinToString("\n\n") { "${it["title"]}\n${it["url"]}\n${it["snippet"]}" }
            ToolResult.ok(output, System.currentTimeMillis() - start)

        } catch (e: Exception) {
            Timber.e(e, "WebSearchTool failed: $query")
            ToolResult.error("Search failed: ${e.message}")
        }
    }
}
