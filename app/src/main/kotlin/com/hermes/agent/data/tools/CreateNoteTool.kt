package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.l3ad3r1.octojotter.data.local.NoteEntity
import com.l3ad3r1.octojotter.data.repository.NoteRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write a Markdown note into Octo Jotter (`:feature:jotter`).
 *
 * This is the first agent tool that reaches across a feature-module boundary: it injects
 * Jotter's [NoteRepository] out of the unified Hilt graph (bound by
 * `com.l3ad3r1.octojotter.di.JotterModule`).
 *
 * Not to be confused with [NotesTool], whose tool name is `notes` and which stores
 * *long-term memories* (facts the agent should remember). This one creates a real,
 * user-visible Markdown document in the notes app.
 *
 * The note is saved locally with `needsSync = false`. Pushing it to a GitHub Gist requires
 * a configured token and is left to Jotter's own sync flow; see PROGRESS.md.
 */
@Singleton
class CreateNoteTool @Inject constructor(
    private val noteRepository: NoteRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "create_note",
        description = "Create a Markdown note in the user's Octo Jotter notebook. Use this " +
            "when the user asks you to write something down, draft a document, or take a " +
            "note they will read later. Do NOT use this to remember facts about the user — " +
            "use the 'notes' tool for that.",
        parameters = listOf(
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "Short title for the note.",
            ),
            ToolParameter(
                name = "content",
                type = ToolParameterType.STRING,
                description = "Body of the note, in Markdown.",
            ),
        ),
        category = "productivity",
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()

        val title = arguments["title"]?.extractString()?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("missing required parameter: title")
        val content = arguments["content"]?.extractString()
            ?: return ToolResult.error("missing required parameter: content")

        return try {
            val id = noteRepository.insertNote(NoteEntity(title = title, content = content))
            ToolResult.ok("note created in Octo Jotter (id=$id, title=\"$title\")")
        } catch (e: Exception) {
            ToolResult.error("could not create note: ${e.message}")
        }.copy(executionMs = System.currentTimeMillis() - start)
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
