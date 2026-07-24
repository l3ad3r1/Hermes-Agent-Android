package com.hermes.agent.data.tools

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.data.llm.ToolCall
import com.hermes.agent.data.tool.ToolCallExecutor
import com.hermes.agent.domain.repository.AgentTaskRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolRegistry
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit capabilities granted to isolated delegated agents. */
internal object DelegateChildCapabilities {
    val allowedToolNames: Set<String> = setOf(
        "web_search",
        "web_fetch",
        "calculator",
        "get_current_datetime",
        "search_conversations",
    )

    fun allows(toolName: String): Boolean = toolName in allowedToolNames
}

/**
 * Spawn one or more isolated subagents to handle focused subtasks, then return
 * their results to the parent. Ported from hermes-agent's `delegate_tool.py`.
 *
 * Each subagent runs with a **fresh context** (none of the parent's
 * conversation history), a focused system prompt built from the delegated
 * goal, and a **restricted toolset** — read/research/compute tools only. The
 * explicit allowlist ([DelegateChildCapabilities]) grants only read/research
 * operations. New tools remain unavailable until deliberately reviewed and added,
 * so recursion, user interaction, writes, scheduling, and device/code side effects
 * cannot appear merely because a tool was registered globally. The parent blocks
 * until every subagent finishes and only sees
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
    private val toolCallExecutor: dagger.Lazy<ToolCallExecutor>,
    private val agentTaskRepository: dagger.Lazy<AgentTaskRepository>,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "delegate",
        description = "Delegate one or more self-contained subtasks to isolated subagents and get " +
            "their results back. Use this to parallelise independent workstreams (e.g. draft three " +
            "variants, analyse several items at once) or to keep a focused subtask out of the main " +
            "context. Provide a single `prompt`, or a `prompts` array of up to $MAX_SUBAGENTS subtasks " +
            "(run one after another). Each subagent starts fresh with no memory of this conversation and has only " +
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
            ToolParameter(
                name = "background",
                type = ToolParameterType.BOOLEAN,
                description = "If true, run the task in the background instead of blocking this " +
                    "turn: it is queued as a delegated task, survives the app being closed, and " +
                    "the user gets a notification with the result. Use for long-running or " +
                    "deferrable work. Background tasks cannot use tools that need user approval.",
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

        val background = (arguments["background"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
        if (background) {
            // Queue via WorkManager instead of blocking this turn. Persisting
            // the task also schedules its worker (L-005 — see the repository);
            // the worker runs a BACKGROUND-origin turn, so the execution
            // policy denies never-autonomous and confirmation-required tools.
            val queued = goals.map { goal ->
                agentTaskRepository.get().add(label = goal.take(60), prompt = goal)
            }
            return ToolResult.ok(
                "Queued ${queued.size} background task(s): " +
                    queued.joinToString("; ") { it.label } +
                    ". The user will get a notification with each result — do not wait for it.",
                System.currentTimeMillis() - start,
            )
        }

        // Run subagents sequentially. Firing 3+ LLM completions at one cloud
        // endpoint concurrently reliably tripped provider-side timeouts/rate
        // limits on-device (2 of 3 would fail), so we trade wall-clock overlap
        // for reliability — the isolation/decomposition value is unchanged.
        val results = goals.map { goal -> runSubagent(goal) }

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
            LlmMessage(
                role = "system",
                content = SUBAGENT_SYSTEM_PROMPT + com.hermes.agent.data.agent.ToolCallPrompt.INSTRUCTION,
            ),
            LlmMessage(role = "user", content = goal),
        )
        val decision = router.route(messages)
        if (decision is RoutingDecision.Unavailable) {
            return "[subagent unavailable: ${decision.reason}]"
        }
        val provider = decision.provider
        val childTools = toolRegistry.get().all()
            .map { it.descriptor }
            .filter { DelegateChildCapabilities.allows(it.name) }

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

    /** Execute a subagent's tool call, enforcing the explicit child allowlist. */
    private suspend fun executeChildTool(call: ToolCall): String {
        if (!DelegateChildCapabilities.allows(call.name)) {
            return "[tool '${call.name}' is not available to subagents]"
        }
        val result = toolCallExecutor.get().execute(
            call = call,
            // The child allowlist contains only non-confirming read tools. If a
            // descriptor changes later, fail closed instead of approving it.
            confirmationGate = ToolCallExecutor.ConfirmationGate { _, _ -> false },
        )
        return (if (result.success) result.output else result.errorMessage ?: "(tool error)")
            .ifBlank { "(no output)" }
            .take(CHILD_TOOL_OUTPUT_CAP)
    }

    private companion object {
        const val MAX_SUBAGENTS = 4
        const val MAX_TOOL_ROUNDS = 4
        const val MAX_RESULT_CHARS = 4000
        const val CHILD_TOOL_OUTPUT_CAP = 2000

        const val SUBAGENT_SYSTEM_PROMPT =
            "You are a focused Jeeves subagent. You have been given a single, self-contained task " +
                "by a parent agent. You have a limited set of read/research tools (web search and " +
                "fetch, calculator, current date/time, conversation search) and cannot ask follow-up " +
                "questions, so make reasonable assumptions where needed. Use tools only when they " +
                "materially help. Return only the result — concise, directly usable by the parent, " +
                "with no preamble or meta-commentary."
    }
}
