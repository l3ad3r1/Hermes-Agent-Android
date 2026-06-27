package com.hermes.agent.data.tools

import com.hermes.agent.data.terminal.TerminalSessionManager
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

private const val MAX_OUTPUT_CHARS = 4000

/**
 * Runs a command in the app's **shared, interactive** terminal session (the
 * same `/system/bin/sh` shown in the Chat → Terminal tab), so the command and
 * its output are visible to the user live. State (cwd, shell variables) persists
 * across calls because it's one long-lived shell.
 *
 * Contrast with [ShellTool], which spawns a fresh, isolated process per call.
 */
@Singleton
class TerminalTool @Inject constructor(
    private val terminal: TerminalSessionManager,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "terminal",
        description = "Run a command in the app's persistent interactive terminal (the shell shown " +
            "in the Terminal tab). Unlike 'shell', state persists between calls — you can 'cd' into a " +
            "directory or set a variable and the next call sees it, and the user watches it run live. " +
            "Returns the exit code plus combined stdout+stderr (capped at $MAX_OUTPUT_CHARS chars). " +
            "Runs as the app user (not root) on the device's /system/bin/sh.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = ToolParameterType.STRING,
                description = "The shell command to run, e.g. 'cd /sdcard && ls -la' or 'uname -a'.",
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
            val raw = terminal.run(command)
            val output = if (raw.length > MAX_OUTPUT_CHARS) raw.take(MAX_OUTPUT_CHARS) + "\n...[truncated]" else raw
            ToolResult.ok(output = output, executionMs = System.currentTimeMillis() - start)
        }.getOrElse { t ->
            ToolResult.error(
                message = "terminal execution failed: ${t.message ?: t.javaClass.simpleName}",
                executionMs = System.currentTimeMillis() - start,
            )
        }
    }
}
