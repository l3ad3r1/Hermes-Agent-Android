package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search the user's Octo Jotter ("Second Brain") notes by keyword.
 */
@Singleton
class SearchNotesTool @Inject constructor(
    private val noteRepository: NoteRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "search_notes",
        description = "Search the user's notes and documents in their Second Brain by keyword. " +
            "Use this when the user asks you a question about their personal notes, projects, or documents.",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "The keyword query to search for.",
            ),
        ),
        category = "productivity",
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()

        val query = arguments["query"]?.extractString()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("missing required parameter: query")

        return try {
            val results = noteRepository.searchPromptSafeNotes(query).first()
            if (results.isEmpty()) {
                ToolResult.ok("No notes found matching: '$query'", System.currentTimeMillis() - start)
            } else {
                // Return top 5 results to avoid overflowing the LLM context window with tool output
                val formatted = results.take(5).joinToString("\n---\n") { note ->
                    "Title: ${note.title}\nID: ${note.id}\n${note.content.take(500)}"
                }
                ToolResult.ok(formatted, System.currentTimeMillis() - start)
            }
        } catch (e: Exception) {
            ToolResult.error("could not search notes: ${e.message}", System.currentTimeMillis() - start)
        }
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
