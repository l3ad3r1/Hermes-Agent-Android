package com.hermes.agent.domain.tool

import kotlinx.serialization.json.JsonElement

/**
 * Type of a tool parameter. Mirrors the JSON Schema primitive types so a
 * tool definition can be serialized into an OpenAI-compatible
 * `tools` array verbatim.
 */
enum class ToolParameterType(val jsonSchemaType: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ARRAY("array"),
    OBJECT("object"),
}

/**
 * One declared parameter of a [ToolDescriptor].
 *
 * @property name Parameter name as the LLM must emit it in a tool call.
 * @property type JSON-Schema primitive type.
 * @property description Natural-language description; the LLM uses this to
 *   decide when and how to supply the parameter.
 * @property required True if the LLM must always supply this parameter.
 * @property enumValues Optional closed set of allowed values.
 */
data class ToolParameter(
    val name: String,
    val type: ToolParameterType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/**
 * Static descriptor advertising a tool's capabilities to the LLM.
 *
 * Serialized into the `tools` field of an OpenAI chat-completion request
 * (or the equivalent field of any other function-calling-capable backend).
 *
 * @property name Stable unique identifier (e.g. `"web_search"`).
 * @property description Human-readable summary of what the tool does and
 *   when the LLM should invoke it.
 * @property parameters Declared parameters in invocation order.
 * @property category Logical group used by the Settings UI to organize
 *   tools ("device", "information", "productivity", "communication").
 * @property requiresConfirmation True if executing this tool has side
 *   effects the user should confirm before invocation (e.g. sending an
 *   SMS, deleting a file). The orchestrator surfaces a confirmation
 *   dialog before running such tools.
 */
data class ToolDescriptor(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val category: String = "general",
    val requiresConfirmation: Boolean = false,
)

/**
 * Result returned by a [Tool] invocation.
 *
 * @property success True if the tool ran without throwing.
 * @property output Natural-language text the LLM should see as the tool's
 *   reply. For structured tools, this is typically a JSON string.
 * @property errorMessage Human-readable error message when [success] is
 *   false.
 * @property executionMs Wall-clock duration of the invocation, for
 *   diagnostic display.
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val errorMessage: String? = null,
    val executionMs: Long = 0L,
) {
    companion object {
        fun ok(output: String, executionMs: Long = 0L) =
            ToolResult(success = true, output = output, executionMs = executionMs)

        fun error(message: String, executionMs: Long = 0L) =
            ToolResult(success = false, output = "", errorMessage = message, executionMs = executionMs)
    }
}

/**
 * Contract every Hermes tool must satisfy.
 *
 * Tools are stateless singletons: any per-call state lives in the
 * invocation arguments. They are registered in [ToolRegistry] at app
 * startup and discovered by the [com.hermes.agent.domain.agent.Orchestrator]
 * when an agent needs to invoke them.
 *
 * Implementations live under `com.hermes.agent.data.tools.*` and are
 * wired into the Hilt graph via [com.hermes.agent.di.ToolsModule].
 */
interface Tool {

    /** Static capability advertisement. */
    val descriptor: ToolDescriptor

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments Named arguments keyed by [ToolParameter.name]. The
     *   orchestrator parses these from the LLM's tool-call payload.
     *   Missing optional parameters are absent from the map; missing
     *   required parameters cause the tool to return a [ToolResult.error].
     */
    suspend fun execute(arguments: Map<String, JsonElement>): ToolResult
}
