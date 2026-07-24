package com.hermes.agent.data.tools

import com.hermes.agent.domain.model.CronPresets
import com.hermes.agent.domain.model.ScheduledTask
import com.hermes.agent.domain.repository.CronRepository
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import com.hermes.agent.util.IdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM-callable tool for managing Hermes scheduled tasks (cron jobs).
 *
 * Actions:
 *   create  — schedule a recurring prompt with a cron expression
 *   list    — show all scheduled tasks and their status
 *   delete  — remove a scheduled task by id
 *   toggle  — enable or disable a task without deleting it
 *
 * Cron expression shortcuts accepted by action='create':
 *   "hourly", "daily", "daily_morning", "daily_evening", "weekdays", "weekly"
 * Or any standard 5-field cron expression: "0 8 * * *"
 */
@Singleton
class SchedulerTool @Inject constructor(
    private val cronRepository: CronRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "scheduler",
        description = "Manage Jeeves scheduled tasks (cron jobs). " +
            "Use action='create' to schedule a recurring prompt. " +
            "Use action='list' to see all scheduled tasks. " +
            "Use action='delete' with task_id to remove a task. " +
            "Use action='toggle' with task_id to enable or disable a task.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "'create', 'list', 'delete', or 'toggle'.",
                enumValues = listOf("create", "list", "delete", "toggle"),
            ),
            ToolParameter(
                name = "label",
                type = ToolParameterType.STRING,
                description = "Short name for the task (required for action='create').",
                required = false,
            ),
            ToolParameter(
                name = "prompt",
                type = ToolParameterType.STRING,
                description = "The prompt Jeeves will run on schedule (required for action='create').",
                required = false,
            ),
            ToolParameter(
                name = "schedule",
                type = ToolParameterType.STRING,
                description = "When to run the task (required for action='create'). " +
                    "Use a preset: 'hourly', 'daily', 'daily_morning', 'daily_evening', 'weekdays', 'weekly'. " +
                    "Or provide a 5-field cron expression directly, e.g. '0 8 * * *' for daily at 8 am.",
                required = false,
            ),
            ToolParameter(
                name = "task_id",
                type = ToolParameterType.STRING,
                description = "Task id (required for action='delete' and action='toggle').",
                required = false,
            ),
        ),
        category = "productivity",
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: action")

        return when (action) {
            "create" -> {
                val label = (arguments["label"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='create' requires parameter: label")
                val prompt = (arguments["prompt"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='create' requires parameter: prompt")
                val scheduleInput = (arguments["schedule"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='create' requires parameter: schedule")

                val cronExpr = parseCronExpression(scheduleInput)
                    ?: return ToolResult.error(
                        "Invalid schedule '$scheduleInput'. Use a preset (hourly, daily, daily_morning, " +
                            "daily_evening, weekdays, weekly) or a 5-field cron expression like '0 8 * * *'."
                    )

                val task = ScheduledTask(
                    id = IdGenerator.newId(),
                    label = label,
                    prompt = prompt,
                    cronExpression = cronExpr,
                    isEnabled = true,
                    createdAt = System.currentTimeMillis(),
                )
                cronRepository.add(task)
                ToolResult.ok(
                    "Scheduled task created.\n" +
                        "- Label: $label\n" +
                        "- Schedule: ${CronPresets.labelFor(cronExpr)} ($cronExpr)\n" +
                        "- Prompt: $prompt\n" +
                        "- ID: ${task.id}",
                    System.currentTimeMillis() - start,
                )
            }

            "list" -> {
                val tasks = cronRepository.observe().first()
                if (tasks.isEmpty()) {
                    return ToolResult.ok("No scheduled tasks yet. Use action='create' to add one.", System.currentTimeMillis() - start)
                }
                val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                val out = buildString {
                    appendLine("## Scheduled Tasks (${tasks.size})")
                    tasks.forEach { t ->
                        val status = if (t.isEnabled) "enabled" else "disabled"
                        val lastRun = t.lastRunAt?.let { fmt.format(Date(it)) } ?: "never"
                        appendLine()
                        appendLine("**${t.label}** [$status]")
                        appendLine("  ID: ${t.id}")
                        appendLine("  Schedule: ${CronPresets.labelFor(t.cronExpression)} (${t.cronExpression})")
                        appendLine("  Prompt: ${t.prompt.take(80)}${if (t.prompt.length > 80) "…" else ""}")
                        appendLine("  Last run: $lastRun")
                        t.lastResult?.let { appendLine("  Last result: ${it.take(100)}") }
                    }
                }
                ToolResult.ok(out.trim(), System.currentTimeMillis() - start)
            }

            "delete" -> {
                val id = (arguments["task_id"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='delete' requires parameter: task_id")
                cronRepository.delete(id)
                ToolResult.ok("Scheduled task $id deleted.", System.currentTimeMillis() - start)
            }

            "toggle" -> {
                val id = (arguments["task_id"] as? JsonPrimitive)?.contentOrNull
                    ?: return ToolResult.error("action='toggle' requires parameter: task_id")
                cronRepository.toggle(id)
                ToolResult.ok("Scheduled task $id toggled.", System.currentTimeMillis() - start)
            }

            else -> ToolResult.error("Unknown action '$action'. Use 'create', 'list', 'delete', or 'toggle'.")
        }
    }

    private fun parseCronExpression(input: String): String? {
        val trimmed = input.trim().lowercase()
        return when (trimmed) {
            "hourly"        -> CronPresets.HOURLY
            "daily",
            "daily_morning" -> CronPresets.DAILY_MORNING
            "daily_evening" -> CronPresets.DAILY_EVENING
            "weekdays"      -> CronPresets.WEEKDAYS
            "weekly"        -> CronPresets.WEEKLY
            else -> {
                // Accept any 5-field cron expression (basic validation).
                val fields = trimmed.split(" ")
                if (fields.size == 5) trimmed else null
            }
        }
    }
}
