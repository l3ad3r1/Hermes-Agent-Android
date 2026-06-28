package com.hermes.agent.data.tools

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spawn one or more isolated subagents to handle focused subtasks, then return
 * their results to the parent. Ported from hermes-agent's `delegate_tool.py`.
 *
 * Each subagent runs with a **fresh context** (none of the parent's
 * conversation history), a focused system prompt built from the delegated
 * goal, and a **restricted toolset** — read/research/compute tools only. The
 * blocklist ([CHILD_BLOCKED_TOOLS]) strips recursion (`delegate`), user
 * interaction (`clarify`), shared-state writes (`memory`, `notes`, `todo`),
 * scheduling, and device/network/code side effects, mirroring upstream's
 * `DELEGATE_BLOCKED_TOOLS`. Stripping `delegate` makes recursive delegation
 * impossible. The parent blocks until every subagent finishes and only sees
 * the summarised results, never their intermediate reasoning or tool calls.
 *
 * Supply a single `prompt`, or a `prompts` array to fan out in parallel.
 *
 * [ToolRegistry] is injected lazily because the registry is built *from* this
 * tool (see ToolsModule) — a direct dependency would be a Hilt construction
 * cycle; [dagger.Lazy] defers resolution until the first delegation.
 */
@Singleton
class DelegateTool @Inject constructor(
    private val router: LlmRouter,
    private val toolRegistry: dagger.Lazy<ToolRegistry>,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "delegate",
        description = "Delegate one or more self-contained subtasks to isolated subagents and get " +
            "their results back. Use this to parallelise independent workstreams (e.g. draft three " +
            "variants, analyse several items at once) or to keep a focused subtask out of the main " +
            "context. Provide a single `prompt`, or a `prompts` array to run up to $MAX_SUBAGENTS in " +
            "parallel. Each subagent starts fresh with no memory of this conversation and has only " +
            "read/research tools (web search/fetch, calculator, date, conversation search) — it " +
            "cannot delegate, ask you questions, or write memory/files — so make every prompt fully " +
            "self-contained. The call blocks until all subagents finish.",
        parameters = listOf(
            ToolParameter(
                name = "prompt",
                type = ToolParameterType.STRING,
                description = "A single self-contained subtask for one subagent.",
                required = false,
            ),
            ToolParameter(
                name = "prompts",
                type = ToolParameterType.ARRAY,
                description = "Multiple self-contained subtasks (strings) to run in parallel, one " +
                    "subagent each. Combined with `prompt` if both are given.",
                required = false,
            ),
        ),
        category = "productivity",
        maxResultSizeChars = 12_000,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()

        val goals = buildList {
            (arguments["prompt"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf(String::isNotEmpty)?.let(::add)
            (arguments["prompts"] as? JsonArray)?.forEach { el ->
                (el as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            }
        }.take(MAX_SUBAGENTS)

        if (goals.isEmpty()) {
            return ToolResult.error(
                "provide a non-empty `prompt` or `prompts`", System.currentTimeMillis() - start,
            )
        }

        val results = coroutineScope {
            goals.map { goal -> async { runSubagent(goal) } }.map { it.await() }
        }

        val output = if (results.size == 1) {
            results.first()
        } else {
            results.mapIndexed { i, r -> "── Subagent ${i + 1} ──\n$r" }.joinToString("\n\n")
        }
        return ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    /**
     * Run one isolated subagent — fresh context, restricted toolset — and
     * return its reply text. Mirrors the orchestrator's LLM↔tool loop but with
     * the child toolset and no user-confirmation gate.
     */
    private suspend fun runSubagent(goal: String): String {
        var messages = listOf(
            LlmMessage(role = "system", content = SUBAGENT_SYSTEM_PROMPT),
            LlmMessage(role = "user", content = goal),
        )
        val decision = router.route(messages)
        if (decision is RoutingDecision.Unavailable) {
            return "[subagent unavailable: ${decision.reason}]"
        }
        val provider = decision.provider
        val childTools = toolRegistry.get().all()
            .map { it.descriptor }
            .filter { it.name !in CHILD_BLOCKED_TOOLS }

        return runCatching {
            repeat(MAX_TOOL_ROUNDS) {
                val response = provider.completeWithTools(messages, childTools)
                if (response.toolCalls.isEmpty()) {
                    return response.content.trim()
                        .ifBlank { "[subagent returned no output]" }.take(MAX_RESULT_CHARS)
                }
                messages = messages + LlmMessage(
                    role = "assistant",
                    content = response.content,
                    toolCalls = response.toolCalls,
                )
                for (call in response.toolCalls) {
                    messages = messages + LlmMessage(
                        role = "tool",
                        content = executeChildTool(call),
                        toolCallId = call.id,
                    )
                }
            }
            // Rounds exhausted while still tool-calling: force a final answer.
            provider.complete(messages).content.trim()
                .ifBlank { "[subagent did not finish]" }.take(MAX_RESULT_CHARS)
        }.getOrElse { t -> "[subagent failed: ${t.message ?: "unknown error"}]" }
    }

    /** Execute a subagent's tool call, enforcing the child blocklist. */
    private suspend fun executeChildTool(call: ToolCall): String {
        if (call.name in CHILD_BLOCKED_TOOLS) {
            return "[tool '${call.name}' is not available to subagents]"
        }
        val tool = toolRegistry.get().byName(call.name)
            ?: return "[unknown tool '${call.name}']"
        val result = runCatching { tool.execute(call.arguments) }
            .getOrElse { return "[tool '${call.name}' error: ${it.message ?: "unknown"}]" }
        return (if (result.success) result.output else result.errorMessage ?: "(tool error)")
            .ifBlank { "(no output)" }
            .take(CHILD_TOOL_OUTPUT_CAP)
    }

    private companion object {
        const val MAX_SUBAGENTS = 4
        const val MAX_TOOL_ROUNDS = 4
        const val MAX_RESULT_CHARS = 4000
        const val CHILD_TOOL_OUTPUT_CAP = 2000

        /**
         * Tools a subagent must never have, mirroring upstream's
         * DELEGATE_BLOCKED_TOOLS: no recursion, no user interaction, no
         * shared-state writes, no scheduling, no device/network/code side
         * effects. What's left is the read/research/compute set.
         */
        val CHILD_BLOCKED_TOOLS = setOf(
            "delegate",            // no recursive delegation
            "clarify",             // children can't interact with the user
            "memory", "notes",     // no writes to shared long-term memory
            "todo",                // no mutating the parent's shared todo list
            "scheduler",           // no scheduling work in the parent's name
            "skill_manager",       // no skill writes
            "speak",               // no audio side effects from a background child
            "shell", "terminal", "termux", // no code execution
            "device_settings",     // no mutating the device
            "calendar_add_event",  // no calendar writes
            "notify",              // no outbound webhooks / cross-platform sends
            "generate_image",      // no paid image generation from a child
        )

        const val SUBAGENT_SYSTEM_PROMPT =
            "You are a focused Hermes subagent. You have been given a single, self-contained task " +
                "by a parent agent. You have a limited set of read/research tools (web search and " +
                "fetch, calculator, current date/time, conversation search) and cannot ask follow-up " +
                "questions, so make reasonable assumptions where needed. Use tools only when they " +
                "materially help. Return only the result — concise, directly usable by the parent, " +
                "with no preamble or meta-commentary."
    }
}
