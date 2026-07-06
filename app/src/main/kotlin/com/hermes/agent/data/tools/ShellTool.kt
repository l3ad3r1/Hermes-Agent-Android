package com.hermes.agent.data.tools

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.terminal.RemoteTerminalBackend
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
private const val REMOTE_TIMEOUT_SECONDS = 30L

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
class ShellTool @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val remoteBackend: RemoteTerminalBackend,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "shell",
        description = "Execute a shell command and return the combined stdout+stderr output " +
            "(capped at $MAX_OUTPUT_CHARS chars). target='local' (default) runs on this device " +
            "as the app user — not root (${TIMEOUT_SECONDS}s timeout). target='remote' runs over " +
            "SSH on the host configured in Settings → Remote shell (${REMOTE_TIMEOUT_SECONDS}s " +
            "timeout; reach Docker via 'docker exec …'). Use for listing files, inspecting state, " +
            "or any shell task.",
        parameters = listOf(
            ToolParameter(
                name = "command",
                type = ToolParameterType.STRING,
                description = "The shell command to execute, e.g. 'ls /sdcard/Download' or 'date'.",
            ),
            ToolParameter(
                name = "target",
                type = ToolParameterType.STRING,
                description = "'local' (default, on-device) or 'remote' (SSH host from Settings).",
                required = false,
                enumValues = listOf("local", "remote"),
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

            val target = (arguments["target"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (target == "remote") {
                return@withContext executeRemote(command, start)
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

    /**
     * Run [command] over SSH on the host configured in Settings → Remote
     * shell. Reads the current SSH config, delegates to [remoteBackend],
     * and formats the result in the same `exit_code=…\n<output>` shape the
     * local path produces so the LLM sees a consistent transcript.
     */
    private suspend fun executeRemote(command: String, start: Long): ToolResult {
        val s = runCatching { settingsRepository.current() }.getOrNull()
            ?: return ToolResult.error("could not read remote shell settings")

        val config = RemoteTerminalBackend.Config(
            host = s.sshHost,
            port = s.sshPort,
            username = s.sshUser,
            password = s.sshPassword,
        )
        if (!config.isConfigured) {
            return ToolResult.error(
                "remote shell is not configured — set the host, port, and username in " +
                    "Settings → Remote shell before using target='remote'.",
            )
        }

        return remoteBackend.execute(config, command, REMOTE_TIMEOUT_SECONDS * 1000).fold(
            onSuccess = { r ->
                val output = r.output
                    .let { if (it.length > MAX_OUTPUT_CHARS) it.take(MAX_OUTPUT_CHARS) + "\n...[truncated]" else it }
                val resultText = buildString {
                    append("exit_code=${r.exitCode} (remote ${s.sshUser}@${s.sshHost})\n")
                    if (output.isNotEmpty()) append(output)
                }
                ToolResult.ok(output = resultText, executionMs = System.currentTimeMillis() - start)
            },
            onFailure = { t ->
                ToolResult.error(
                    message = "remote shell failed: ${t.message ?: t.javaClass.simpleName}",
                    executionMs = System.currentTimeMillis() - start,
                )
            },
        )
    }
}
