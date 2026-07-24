package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

internal data class LocalPrompt(
    val system: String,
    val conversation: String,
)

/** Builds a bounded, role-labelled transcript without model-specific template tokens. */
internal fun buildLocalPrompt(
    messages: List<LlmMessage>,
    maxConversationChars: Int = 12_000,
): LocalPrompt {
    val system = messages.filter { it.role == "system" }
        .joinToString("\n\n") { it.content.trim() }
        .trim()
    val rendered = messages.filterNot { it.role == "system" }.map { message ->
        val label = when (message.role) {
            "user" -> "User"
            "assistant" -> "Assistant"
            "tool" -> "Tool result${message.toolCallId?.let { " ($it)" }.orEmpty()}"
            else -> message.role.replaceFirstChar { it.uppercase() }
        }
        val toolRequests = message.toolCalls.orEmpty().joinToString("\n") { call ->
            "Requested tool ${call.name} with arguments ${call.argumentsJson()}"
        }
        buildString {
            append(label).append(":\n").append(message.content.trim())
            if (toolRequests.isNotBlank()) {
                if (message.content.isNotBlank()) append('\n')
                append(toolRequests)
            }
        }
    }

    val selected = ArrayDeque<String>()
    var used = 0
    for (entry in rendered.asReversed()) {
        val cost = entry.length + if (selected.isEmpty()) 0 else 2
        if (selected.isNotEmpty() && used + cost > maxConversationChars) break
        val fitted = if (entry.length <= maxConversationChars) {
            entry
        } else {
            val prefix = entry.substringBefore('\n') + "\n"
            prefix + entry.takeLast((maxConversationChars - prefix.length).coerceAtLeast(0))
        }
        selected.addFirst(fitted)
        used += cost.coerceAtMost(maxConversationChars)
    }
    return LocalPrompt(
        system = system,
        conversation = selected.joinToString("\n\n").trim(),
    )
}

class LocalLlmProvider @Inject constructor(
    private val localLlmManager: LocalLlmManager,
    private val json: Json,
) : LlmProvider {
    override val name: String = "On-device model"
    override val isOnDevice: Boolean = true
    override val model: String = "local-gguf"

    override suspend fun complete(messages: List<LlmMessage>): LlmResponse {
        val response = StringBuilder()
        stream(messages).collect { chunk ->
            when (chunk) {
                is LlmStreamChunk.Delta -> response.append(chunk.text)
                is LlmStreamChunk.Error -> error(chunk.message)
                is LlmStreamChunk.ToolCallDelta,
                LlmStreamChunk.Done,
                -> Unit
            }
        }
        return LlmResponse(
            content = response.toString(),
            tokensUsed = 0,
            model = model,
            finishReason = "stop",
        )
    }

    override fun stream(messages: List<LlmMessage>): Flow<LlmStreamChunk> = flow {
        val prompt = buildLocalPrompt(messages)
        if (prompt.conversation.isBlank()) {
            emit(LlmStreamChunk.Error("The local model needs a user or tool message."))
            return@flow
        }
        localLlmManager.generateResponse(prompt.system, prompt.conversation).collect { token ->
            emit(LlmStreamChunk.Delta(token))
        }
        emit(LlmStreamChunk.Done)
    }

    override suspend fun completeWithTools(
        messages: List<LlmMessage>,
        tools: List<ToolDescriptor>,
    ): LlmToolResponse {
        val augmentedMessages = if (tools.isEmpty()) messages else messages.withLocalToolInstructions(tools)
        val response = complete(augmentedMessages)
        val (content, toolCalls) = extractTextToolCalls(response.content, json)
        return LlmToolResponse(
            content = content,
            toolCalls = toolCalls,
            tokensUsed = response.tokensUsed,
            model = response.model,
            finishReason = if (toolCalls.isEmpty()) response.finishReason else "tool_calls",
        )
    }

    override suspend fun isAvailable(): Boolean = localLlmManager.isModelDownloaded()
}

private fun List<LlmMessage>.withLocalToolInstructions(tools: List<ToolDescriptor>): List<LlmMessage> {
    val instruction = buildString {
        appendLine("You may use only the tools listed below.")
        tools.forEach { tool ->
            append("- ").append(tool.name).append(": ").append(tool.description)
            if (tool.parameters.isNotEmpty()) {
                append(" Arguments: ")
                tool.parameters.joinTo(this, ", ") { parameter ->
                    "${parameter.name} (${parameter.type.name.lowercase()}${if (parameter.required) ", required" else ""})"
                }
            }
            appendLine()
        }
        append(
            "To call a tool, reply with exactly " +
                "<tool_call>{\"name\":\"tool_name\",\"arguments\":{}}</tool_call>. " +
                "Otherwise answer normally. Never invent a tool name.",
        )
    }
    val systemIndex = indexOfFirst { it.role == "system" }
    return if (systemIndex >= 0) {
        toMutableList().apply {
            this[systemIndex] = this[systemIndex].copy(
                content = this[systemIndex].content + "\n\n" + instruction,
            )
        }
    } else {
        listOf(LlmMessage(role = "system", content = instruction)) + this
    }
}
