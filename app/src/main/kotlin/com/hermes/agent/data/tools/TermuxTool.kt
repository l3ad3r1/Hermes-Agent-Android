package com.hermes.agent.data.tools

import com.hermes.agent.data.terminal.TermuxCommandRunner
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_OUTPUT_CHARS = 6000

/**
 * Runs a command in the user's installed **Termux** app (full Linux env: apt/pkg,
 * python, git, ssh, compilers, …) via Termux's RUN_COMMAND intent, returning the
 * exit code and stdout/stderr.
 *
 * Use this (over `terminal`/`shell`) when the task needs real Linux packages or
 * tooling. Requires Termux installed with `allow-external-apps=true`.
 */
@Singleton
class TermuxTool @Inject constructor(
    private val termux: TermuxCommandRunner,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "termux",
        description = "Run a command in the user's Termux app — a full Linux environment with a " +
            "package manager (pkg/apt), python, git, ssh, compilers and more. Prefer this when the " +
            "task needs real Linux tooling or packages. Returns exit code + stdout/stderr (capped at " +
            "$MAX_OUTPUT_CHARS chars). Requires Termux installed with allow-external-apps=true; if it " +
            "isn't reachable the result explains how to enable it. For quick device/system commands, " +
            "use 'terminal' or 'shell' instead.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = ToolParameterType.STRING,
                description = "The bash command to run in Termux, e.g. 'pkg install -y jq' or " +
                    "'python3 -c \"print(1+1)\"' or 'git clone https://… repo'.",
            ),
        ),
        category = "device",
        requiresConfirmation = true,
        maxResultSizeChars = MAX_OUTPUT_CHARS,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val command = (arguments["command"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: return ToolResult.error("missing required parameter: command")
        if (command.isEmpty()) return ToolResult.error("command must not be empty")

        return runCatching {
            val raw = termux.run(command)
            val output = if (raw.length > MAX_OUTPUT_CHARS) raw.take(MAX_OUTPUT_CHARS) + "\n...[truncated]" else raw
            ToolResult.ok(output = output, executionMs = System.currentTimeMillis() - start)
        }.getOrElse { t ->
            ToolResult.error(
                message = "termux execution failed: ${t.message ?: t.javaClass.simpleName}",
                executionMs = System.currentTimeMillis() - start,
            )
        }
    }
}
