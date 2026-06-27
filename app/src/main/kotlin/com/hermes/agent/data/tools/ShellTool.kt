package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_OUTPUT_CHARS = 4000
private const val TIMEOUT_SECONDS = 10L

/**
 * Executes a shell command via ProcessBuilder in the app's process context.
 * Commands run as the app user (not root). stdout and stderr are merged and
 * capped at MAX_OUTPUT_CHARS. A hard TIMEOUT_SECONDS timeout is enforced;
 * the process is forcibly destroyed if it exceeds it.
 *
 * requiresConfirmation = true so the orchestrator always surfaces a dialog
 * before running any shell command.
 */
@Singleton
class ShellTool @Inject constructor() : Tool {

    override val descriptor = ToolDescriptor(
        name = "shell",
        description = "Execute a shell command on the device and return the combined stdout+stderr " +
            "output (capped at $MAX_OUTPUT_CHARS chars). The command runs as the app's user " +
            "account — not root. Use for listing files, inspecting device state, running " +
            "adb-shell-compatible commands, or any other shell task. Timeout: ${TIMEOUT_SECONDS}s.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = ToolParameterType.STRING,
                description = "The shell command to execute, e.g. 'ls /sdcard/Download' or 'date'.",
            ),
        ),
        category = "device",
        requiresConfirmation = true,
        maxResultSizeChars = MAX_OUTPUT_CHARS,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val command = (arguments["command"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?: return@withContext ToolResult.error("missing required parameter: command")

            if (command.isEmpty()) {
                return@withContext ToolResult.error("command must not be empty")
            }

            runCatching {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()

                val rawBytes = ByteArrayOutputStream()
                val inputStream = process.inputStream
                val finished = try {
                    val buf = ByteArray(4096)
                    val deadline = System.currentTimeMillis() + TIMEOUT_SECONDS * 1000
                    while (System.currentTimeMillis() < deadline) {
                        val available = inputStream.available()
                        if (available > 0) {
                            val n = inputStream.read(buf, 0, minOf(available, buf.size))
                            if (n > 0) rawBytes.write(buf, 0, n)
                        }
                        if (process.waitFor(50, TimeUnit.MILLISECONDS)) break
                    }
                    // True if the process exited within the deadline
                    process.waitFor(0, TimeUnit.MILLISECONDS).also { exited ->
                        if (!exited) {
                            // Drain any last output before killing
                            rawBytes.write(inputStream.readBytes())
                        }
                    }
                } finally {
                    inputStream.runCatching { close() }
                }

                if (!finished) {
                    process.destroyForcibly()
                    return@runCatching ToolResult.error(
                        message = "command timed out after ${TIMEOUT_SECONDS}s",
                        executionMs = System.currentTimeMillis() - start,
                    )
                }

                val exitCode = process.exitValue()
                val output = rawBytes.toByteArray()
                    .toString(Charsets.UTF_8)
                    .filter { it.code != 0 }
                    .trim()
                    .let {
                        if (it.length > MAX_OUTPUT_CHARS)
                            it.take(MAX_OUTPUT_CHARS) + "\n...[truncated]"
                        else it
                    }

                val resultText = buildString {
                    append("exit_code=$exitCode\n")
                    if (output.isNotEmpty()) append(output)
                }

                ToolResult.ok(
                    output = resultText,
                    executionMs = System.currentTimeMillis() - start,
                )
            }.getOrElse { t ->
                ToolResult.error(
                    message = "shell execution failed: ${t.message ?: t.javaClass.simpleName}",
                    executionMs = System.currentTimeMillis() - start,
                )
            }
        }
}
