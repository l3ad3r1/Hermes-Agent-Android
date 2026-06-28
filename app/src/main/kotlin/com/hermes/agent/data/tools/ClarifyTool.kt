package com.hermes.agent.data.tools

import com.hermes.agent.data.agent.ClarificationBus
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ask the user a clarifying question and wait for their answer. Ported from
 * hermes-agent's `clarify_tool.py`.
 *
 * Upstream renders choices as arrow-key navigable options in the CLI or a
 * numbered list on messaging platforms; here the chat UI surfaces the
 * question with tappable choice chips. The tool suspends on
 * [ClarificationBus] until the user responds, then returns their answer as
 * the tool result so the agent can continue with a concrete decision instead
 * of guessing.
 */
@Singleton
class ClarifyTool @Inject constructor(
    private val bus: ClarificationBus,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "clarify",
        description = "Ask the user a single clarifying question and wait for their answer. Use this " +
            "when the request is ambiguous and proceeding on an assumption risks doing the wrong " +
            "thing — prefer asking once over guessing. Provide up to 4 `choices` for a multiple-choice " +
            "question, or omit them for an open-ended one (the user can always type a free-form reply). " +
            "Returns the user's answer.",
        parameters = listOf(
            ToolParameter(
                name = "question",
                type = ToolParameterType.STRING,
                description = "The question to ask the user.",
            ),
            ToolParameter(
                name = "choices",
                type = ToolParameterType.ARRAY,
                description = "Optional list of up to 4 predefined answer choices (strings).",
                required = false,
            ),
        ),
        category = "communication",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val question = (arguments["question"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (question.isNullOrEmpty()) {
            return ToolResult.error("missing required parameter: question", System.currentTimeMillis() - start)
        }

        val choices = (arguments["choices"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?.take(MAX_CHOICES)
            ?: emptyList()

        return try {
            val answer = bus.ask(question, choices)
            ToolResult.ok(answer.ifBlank { "(no answer)" }, System.currentTimeMillis() - start)
        } catch (t: Throwable) {
            ToolResult.error(
                t.message ?: "clarification was cancelled", System.currentTimeMillis() - start,
            )
        }
    }

    private companion object {
        // Upstream caps predefined choices at 4 (a 5th "type your own" is implicit).
        const val MAX_CHOICES = 4
    }
}
