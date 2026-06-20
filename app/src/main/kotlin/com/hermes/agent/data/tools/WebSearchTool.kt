package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web search stub. Phase 2 returns canned results so the orchestrator and
 * Research agent can be developed and exercised end-to-end without a real
 * search backend.
 *
 * What replaces this in Phase 3:
 *   - A real Retrofit client against a search API (Serper, Brave Search,
 *     Bing, or a self-hosted SearXNG instance). The Tool contract stays
 *     identical — only the body of [execute] changes.
 */
@Singleton
class WebSearchTool @Inject constructor() : Tool {

    override val descriptor = ToolDescriptor(
        name = "web_search",
        description = "Search the public web for current information. Returns the top results " +
            "with title, URL, and a short snippet. Use this when the user asks about recent " +
            "events, current prices, or anything not in your training data.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "The search query, 2–10 words works best.",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of results to return. Defaults to 5.",
                required = false,
            ),
        ),
        category = "information",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val query = arguments["query"]?.extractString()
            ?: return ToolResult.error("missing required parameter: query")
        val limit = arguments["limit"]?.extractString()?.toIntOrNull() ?: 5

        // Phase 2 mock: synthesize plausible-looking results.
        val results = (1..limit.coerceIn(1, 10)).map { i ->
            mapOf(
                "title" to "Result $i for \"$query\"",
                "url" to "https://example.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&i=$i",
                "snippet" to "This is a mock search result. In Phase 3 a real search API " +
                    "(Serper / Brave / SearXNG) would return an actual snippet from the web " +
                    "page at this URL, summarizing why it matched the query.",
            )
        }
        val json = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(
                kotlinx.serialization.serializer<Map<String, String>>()
            ),
            results,
        )
        return ToolResult.ok(output = json, executionMs = System.currentTimeMillis() - start)
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
