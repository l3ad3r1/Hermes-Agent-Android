package com.hermes.agent.data.tools

import com.hermes.agent.data.llm.LlmMessage
import com.hermes.agent.data.llm.LlmRouter
import com.hermes.agent.data.llm.RoutingDecision
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
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
 * goal, and **no tools** — which both isolates it and makes recursive
 * delegation structurally impossible (upstream strips `delegate`, `clarify`,
 * `memory`, etc. from children for the same reasons). The parent blocks until
 * every subagent finishes and only sees the summarised results, never their
 * intermediate reasoning.
 *
 * Supply a single `prompt`, or a `prompts` array to fan out in parallel. The
 * tool-less child is a deliberate first cut; giving children a restricted
 * *toolset* (as upstream does) is follow-up work.
 */
@Singleton
class DelegateTool @Inject constructor(
    private val router: LlmRouter,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "delegate",
        description = "Delegate one or more self-contained subtasks to isolated subagents and get " +
            "their results back. Use this to parallelise independent workstreams (e.g. draft three " +
            "variants, analyse several items at once) or to keep a focused subtask out of the main " +
            "context. Provide a single `prompt`, or a `prompts` array to run up to $MAX_SUBAGENTS in " +
            "parallel. Each subagent starts fresh with no memory of this conversation and has no " +
            "tools, so make every prompt fully self-contained. The call blocks until all subagents " +
            "finish.",
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

    /** Run one isolated, tool-less subagent and return its reply text. */
    private suspend fun runSubagent(goal: String): String {
        val messages = listOf(
            LlmMessage(role = "system", content = SUBAGENT_SYSTEM_PROMPT),
            LlmMessage(role = "user", content = goal),
        )
        val decision = router.route(messages)
        if (decision is RoutingDecision.Unavailable) {
            return "[subagent unavailable: ${decision.reason}]"
        }
        return runCatching { decision.provider.complete(messages).content.trim() }
            .map { it.ifBlank { "[subagent returned no output]" }.take(MAX_RESULT_CHARS) }
            .getOrElse { t -> "[subagent failed: ${t.message ?: "unknown error"}]" }
    }

    private companion object {
        const val MAX_SUBAGENTS = 4
        const val MAX_RESULT_CHARS = 4000
        const val SUBAGENT_SYSTEM_PROMPT =
            "You are a focused Hermes subagent. You have been given a single, self-contained task " +
                "by a parent agent. You have no tools and cannot ask follow-up questions, so make " +
                "reasonable assumptions where needed. Complete the task and return only the result — " +
                "concise, directly usable by the parent, with no preamble or meta-commentary."
    }
}
