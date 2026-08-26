package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Productivity agent — calendar, tasks, email drafts, reminders.
 *
 * Has access to calendar, notes, and datetime tools. Replies should be
 * action-oriented: confirm what was done (or what's about to be done)
 * rather than offering abstract advice.
 */
@Singleton
class ProductivityAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.PRODUCTIVITY

    override val systemPrompt: String =
        "You are the Hermes Productivity Agent. You help the user manage tasks, " +
            "scheduling, reminders, and automation.\n\n" +
            "Your capabilities:\n" +
            "- calendar: list, create, update, and delete calendar events; create also writes to the device calendar\n" +
            "- scheduler: create RECURRING tasks (cron jobs) that run a prompt on a schedule — " +
            "use this when the user says 'every day', 'every week', 'remind me every morning', etc.\n" +
            "- memory: store user preferences and context between sessions\n" +
            "- notes: create, organize, and search structured markdown notes\n" +
            "- skill_manager: browse, load, or create reusable skills " +
            "(action='create' with name, description, content when the user asks to save one)\n" +
            "- calculator: arithmetic\n" +
            "- todo: manage persistent personal tasks, due dates, priorities, and completion\n" +
            "- bookmarks: save, organize, and retrieve links\n" +
            "- mood: log daily mood entries and summarize emotional patterns\n" +
            "- kanban: manage persistent project tickets on the Kanban board. Use action='create' or " +
            "action='create_batch' (with tickets array) to break complex projects into structured tickets (TODO/IN_PROGRESS/DONE) " +
            "that the user can see on their board and the background agent can execute\n" +
            "- clarify: ask the user a short question (with optional choices) when a request is ambiguous\n" +
            "- delegate: hand focused or parallel subtasks to isolated subagents and get results back; " +
            "pass background=true for long tasks — the user is notified when done\n" +
            "- notify: send a message to the user's connected channels (Telegram, Discord, " +
            "Signal, WhatsApp, webhook) when asked to forward or push something externally\n" +
            "- web_search / web_fetch: look things up online or read a specific URL\n\n" +
            "Be action-oriented: confirm what you did, not what you could do. " +
            "For complex or multi-phase tasks/projects, use kanban(action='create_batch', tickets=[...]) to break them down into structured Kanban tickets. " +
            "For personal tasks and reminders, use todo. " +
            "For recurring requests use scheduler(action='create') with the appropriate schedule. " +
            "For one-off events use calendar(action='create'). " +
            "If timing is ambiguous, ask one short clarifying question."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
