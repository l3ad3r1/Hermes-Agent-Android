package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web search via DuckDuckGo HTML endpoint. No API key required.
 *
 * Uses the HTML search page (html.duckduckgo.com) rather than the Instant
 * Answer JSON API, which only returned results for famous/Wikipedia queries
 * and timed out on everything else. The HTML endpoint is the same backend
 * that powers the DuckDuckGo Lite site — it works for any query.
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "web_search",
        description = "Search the web for current information. Returns titles, URLs and snippets for the top results.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "The search query.",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Max results to return (default 8, max 15).",
                required = false,
            ),
        ),
        category = "information",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val query = (arguments["query"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: query")
        val limit = (arguments["limit"] as? JsonPrimitive)?.contentOrNull
            ?.toIntOrNull()?.coerceIn(1, 15) ?: 8

        return try {
            // POST to DuckDuckGo Lite — returns clean HTML, no JS required.
            val body = FormBody.Builder()
                .add("q", query)
                .add("kl", "wt-wt")   // no region bias
                .add("kp", "-2")       // safe-search off
                .build()

            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/")
                .post(body)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()

            val html = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolResult.error("Search request failed: HTTP ${response.code}")
                }
                response.body?.string() ?: return ToolResult.error("Empty response from DuckDuckGo")
            }

            val results = parseResults(html, limit)
            if (results.isEmpty()) {
                return ToolResult.ok("No results found for: \"$query\"", System.currentTimeMillis() - start)
            }

            val output = buildString {
                appendLine("Search results for: \"$query\"\n")
                results.forEachIndexed { i, r ->
                    appendLine("${i + 1}. ${r.title}")
                    appendLine("   ${r.url}")
                    if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
                    appendLine()
                }
            }.trim()

            ToolResult.ok(output, System.currentTimeMillis() - start)

        } catch (e: Exception) {
            Timber.e(e, "WebSearchTool failed: $query")
            ToolResult.error("Search failed: ${e.message}")
        }
    }

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private fun parseResults(html: String, limit: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Each result block is wrapped in <div class="result ..."> or <div class="links_main ...">
        // Extract anchors with class "result__a" for titles/urls, "result__snippet" for snippets.
        val resultBlockRegex = Regex(
            """class="result__title"[^>]*>.*?<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</span>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        resultBlockRegex.findAll(html).take(limit).forEach { match ->
            val rawUrl   = match.groupValues[1].trim()
            val rawTitle = match.groupValues[2]
            val rawSnip  = match.groupValues[3]

            val url     = decodeUrl(rawUrl)
            val title   = stripTags(rawTitle).trim()
            val snippet = stripTags(rawSnip).trim()

            if (title.isNotBlank() && url.isNotBlank()) {
                results += SearchResult(title, url, snippet)
            }
        }

        // Fallback: simpler href+text extraction if the above finds nothing
        if (results.isEmpty()) {
            val fallbackRegex = Regex(
                """<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            fallbackRegex.findAll(html).take(limit).forEach { match ->
                val url   = decodeUrl(match.groupValues[1].trim())
                val title = stripTags(match.groupValues[2]).trim()
                if (title.isNotBlank() && url.startsWith("http")) {
                    results += SearchResult(title, url, "")
                }
            }
        }

        return results
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("&#?\\w+;"), "")
            .trim()

    // DuckDuckGo wraps URLs in //duckduckgo.com/l/?uddg=<encoded-url>
    private fun decodeUrl(raw: String): String {
        if (raw.startsWith("//duckduckgo.com/l/") || raw.startsWith("https://duckduckgo.com/l/")) {
            val uddg = Regex("[?&]uddg=([^&]+)").find(raw)?.groupValues?.get(1) ?: return raw
            return runCatching { java.net.URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(raw)
        }
        return raw
    }
}
