package com.hermes.agent.tool

import com.hermes.agent.data.remote.GatewayApiClient
import com.hermes.agent.data.remote.JOB_ACTIONS
import com.hermes.agent.data.remote.RemoteJob
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controls the scheduled bots (cron jobs) running on the PC Hermes gateway through its
 * `/api/jobs` API. Uses the Remote gateway URL + key from Settings → Connections, and
 * works whether or not the app itself is in thin-client mode.
 */
@Singleton
class DesktopBotsTool @Inject constructor(
    private val gateway: GatewayApiClient,
    private val settingsRepository: SettingsRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "desktop_bots",
        description = "List and control the scheduled bots (cron jobs) on the user's PC Hermes. " +
            "Actions: 'list' (every bot with schedule, state and last result), 'pause', 'resume', " +
            "'run' (trigger now, out of schedule). pause/resume/run need a job_id from 'list'.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "list, pause, resume or run.",
                required = true,
                enumValues = listOf("list") + JOB_ACTIONS,
            ),
            ToolParameter(
                name = "job_id",
                type = ToolParameterType.STRING,
                description = "Job id from 'list' (required for pause, resume and run).",
            ),
            ToolParameter(
                name = "profile",
                type = ToolParameterType.STRING,
                description = "Named PC Hermes profile that owns the bots, when the user names one. " +
                    "Omit for the default profile. Use the same profile for 'list' and the follow-up action.",
            ),
        ),
        category = "automation",
        capabilities = setOf("desktop_bots"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start
        val action = arguments.string("action")?.lowercase()
            ?: return ToolResult.error("Missing required parameter: 'action'", elapsed())

        val settings = settingsRepository.current()
        if (settings.remoteGatewayUrl.isBlank() || settings.remoteGatewayApiKey.isBlank()) {
            return ToolResult.error(
                "The PC gateway is not configured. Set the URL and API key in Settings → Connections → Remote gateway.",
                elapsed(),
            )
        }

        val profile = arguments.string("profile")
        if (profile != null && !NAME_RE.matches(profile)) return ToolResult.error("Invalid profile '$profile'.", elapsed())

        return try {
            when (action) {
                "list" -> ToolResult.ok(formatJobs(gateway.listJobs(profile)), elapsed())
                in JOB_ACTIONS -> {
                    val jobId = arguments.string("job_id")
                        ?: return ToolResult.error("'$action' needs a job_id from 'list'.", elapsed())
                    if (!NAME_RE.matches(jobId)) return ToolResult.error("Invalid job_id '$jobId'.", elapsed())
                    ToolResult.ok("$action ok → ${formatJob(gateway.jobAction(jobId, action, profile))}", elapsed())
                }
                else -> ToolResult.error("Unknown action '$action'. Expected list, pause, resume or run.", elapsed())
            }
        } catch (e: Exception) {
            ToolResult.error("PC gateway request failed: ${e.message ?: e.javaClass.simpleName}", elapsed())
        }
    }

    private fun formatJobs(jobs: List<RemoteJob>): String =
        if (jobs.isEmpty()) "No bots are scheduled on the PC." else jobs.joinToString("\n") { "- ${formatJob(it)}" }

    private fun formatJob(job: RemoteJob): String = buildString {
        append("${job.name.ifBlank { job.id }} [id ${job.id}] ")
        append(if (job.enabled) job.state.ifBlank { "active" } else "paused")
        if (job.schedule.isNotBlank()) append(", schedule ${job.schedule}")
        job.nextRunAt?.let { append(", next $it") }
        job.lastRunAt?.let { append(", last $it ${job.lastStatus.orEmpty()}") }
        job.lastError?.let { append(", error: ${it.take(200)}") }
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** Job ids and profile names become URL path segments, so nothing that could escape one. */
        val NAME_RE = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DesktopBotsToolModule {
    @Binds
    @IntoSet
    abstract fun bindDesktopBotsTool(tool: DesktopBotsTool): Tool
}
