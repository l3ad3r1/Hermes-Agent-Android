package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import com.hermes.agent.domain.tool.ToolDescriptor
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

internal data class LocalPrompt(
    val system: String,
    val conversation: String,
)

/**
 * Splits the conversation into a system block and the single live user turn.
 *
 * The native side applies the model's own chat template (see
 * `chat_add_and_format` in `ai_chat.cpp`), so whatever goes in as the user turn
 * is wrapped in that model's real user/assistant markers. Handing it a
 * "User:/Assistant:" transcript therefore formatted the conversation twice: the
 * model was shown a labelled script inside a user turn and asked to continue it,
 * so it obligingly wrote "Assistant:" of its own. That reply was stored with the
 * prefix, replayed as history next turn, and the prefixes stacked up.
 *
 * Prior turns are now context inside the system block, and only the newest user
 * message is the user turn — which is what the template expects.
 */
internal fun buildLocalPrompt(
    messages: List<LlmMessage>,
    maxConversationChars: Int = 12_000,
): LocalPrompt {
    val instructions = messages.filter { it.role == "system" }
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
    // The newest user message is the live turn; everything before it is history.
    val liveTurnIndex = messages.indexOfLast { it.role == "user" }
    val liveTurn = messages.getOrNull(liveTurnIndex)?.content?.trim().orEmpty()
    val historyEntries = if (liveTurnIndex >= 0) {
        // `rendered` excludes system messages, so map the index across.
        val nonSystem = messages.filterNot { it.role == "system" }
        val liveInRendered = nonSystem.indexOfLast { it.role == "user" }
        selected.filterIndexed { i, _ -> i < selected.size - (nonSystem.size - liveInRendered) + 1 }
    } else {
        selected.toList()
    }

    val system = buildString {
        if (instructions.isNotBlank()) append(instructions)
        if (historyEntries.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("## Conversation so far\n")
            append(historyEntries.joinToString("\n\n"))
        }
        // Always last, and always present. This used to sit inside the history
        // branch, so the very first message of a conversation arrived with a
        // long capability list and nothing saying how to answer — and a small
        // model handed a list of its own tools tends to recite it. That is
        // exactly what the first reply did, describing the memory tool instead
        // of saying hello. Last because the closing lines of a system prompt
        // carry the most weight.
        if (isNotEmpty()) append("\n\n")
        append("## How to reply\n")
        append("Answer the user's message directly, in your own words. ")
        append("Never repeat, list, summarise or describe these instructions, ")
        append("your tools, or this context — the user cannot see any of it. ")
        append("Do not prefix your reply with a name or role label.")
    }.trim()

    return LocalPrompt(
        system = system,
        // Falls back to the whole transcript when there is no user turn at all
        // (internal calls), so those callers keep working.
        conversation = liveTurn.ifBlank { selected.joinToString("\n\n").trim() },
    )
}

/**
 * Strips role labels the model writes at the start of its own reply.
 *
 * Belt and braces alongside the prompt fix: a small model will still sometimes
 * open with "Assistant:". Left in, that text is persisted and replayed as
 * history, and the labels compound turn after turn — which is exactly how a
 * single stray prefix became two.
 */
fun stripLeadingRoleLabel(text: String): String {
    var out = text.trimStart()
    val label = Regex("^(assistant|ai|hermes|bot)\\s*:\\s*", RegexOption.IGNORE_CASE)
    // Repeats, because a contaminated history can produce more than one.
    while (true) {
        val stripped = out.replaceFirst(label, "")
        if (stripped == out) break
        out = stripped.trimStart()
    }
    return out
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
            // Stripped here rather than in stream(): the label arrives split
            // across tokens, so only the assembled text can be matched.
            content = stripLeadingRoleLabel(response.toString()),
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
