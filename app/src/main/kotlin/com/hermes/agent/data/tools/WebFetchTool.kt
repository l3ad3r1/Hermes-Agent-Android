package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a URL and returns the page's readable text content.
 * Strips HTML tags, collapses whitespace, and truncates to a sensible limit
 * so the LLM gets clean context without token-bloat.
 */
@Singleton
class WebFetchTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "web_fetch",
        description = "Fetch the content of a web page and return its readable text. " +
            "Use this to read articles, documentation, or any URL the user shares.",
        parameters = listOf(
            ToolParameter(
                name = "url",
                type = ToolParameterType.STRING,
                description = "The full URL to fetch (must start with http:// or https://).",
            ),
            ToolParameter(
                name = "max_chars",
                type = ToolParameterType.INTEGER,
                description = "Maximum characters to return (default 8000).",
                required = false,
            ),
        ),
        category = "information",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val url = (arguments["url"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.error("missing required parameter: url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.error("url must start with http:// or https://")
        }
        val maxChars = (arguments["max_chars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?.coerceIn(500, 32_000) ?: 8_000

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android) HermesAgent/1.0")
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                .build()

            val raw = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ToolResult.error("HTTP ${response.code} fetching $url")
                }
                response.body?.string() ?: return ToolResult.error("Empty response body from $url")
            }

            val text = extractText(raw, maxChars)
            if (text.isBlank()) return ToolResult.error("No readable content found at $url")

            ToolResult.ok("URL: $url\n\n$text", System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Timber.e(e, "WebFetchTool failed: $url")
            ToolResult.error("Failed to fetch $url: ${e.message}")
        }
    }

    private fun extractText(html: String, maxChars: Int): String {
        // Remove script/style/noscript blocks entirely
        val stripped = html
            .replace(Regex("<(script|style|noscript|head)[^>]*>[\\s\\S]*?</(script|style|noscript|head)>", RegexOption.IGNORE_CASE), " ")
            // Replace block-level tags with newlines to preserve structure
            .replace(Regex("</(p|div|li|h[1-6]|tr|br|blockquote)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            // Strip all remaining tags
            .replace(Regex("<[^>]+>"), "")
            // Decode common HTML entities
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("&#?\\w+;"), " ")
            // Collapse whitespace
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

        return if (stripped.length <= maxChars) stripped
        else stripped.take(maxChars) + "\n…[truncated at $maxChars chars]"
    }
}
