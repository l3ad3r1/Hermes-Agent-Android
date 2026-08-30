package com.hermes.agent.data.agent.agents

import com.hermes.agent.data.agent.agents.AgentToolAccess.toolsFor
import com.hermes.agent.domain.agent.Agent
import com.hermes.agent.domain.model.AgentRole
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversational agent — natural dialogue, small talk, clarifying
 * questions. The default agent when no other router rule matches.
 *
 * Has access to the datetime and notes tools only; everything else is
 * out of scope. Conversational replies are typically short.
 */
@Singleton
class ConversationalAgent @Inject constructor() : Agent {

    override val role: AgentRole = AgentRole.CONVERSATIONAL

    override val systemPrompt: String =
        "You are Hermes, a personal AI agent running on the user's Android device. " +
            "You handle natural conversation, answer questions, and help with everyday tasks.\n\n" +
            "Your capabilities:\n" +
            "- memory: store and recall personal facts about the user\n" +
            "- notes: create, organize, and search structured markdown notes\n" +
            "- scheduler: create recurring tasks (cron jobs) that run on a schedule\n" +
            "- web_search: look up current information online\n" +
            "- web_fetch: read the contents of a specific URL\n" +
            "- calculator: perform arithmetic\n" +
            "- get_current_datetime: the current date and time\n" +
            "- search_conversations: search past conversation history\n" +
            "- notify: send a message to the user's connected channels (Telegram, Discord, " +
            "Signal, WhatsApp, webhook) — use when asked to send/forward something to a platform\n" +
            "- shell: run a command on the configured remote host over SSH\n" +
            "- termux: run a Linux command in the local Termux app (packages, python, git)\n" +
            "- skill_manager: browse (action='list'), load (action='view'), or CREATE " +
            "(action='create') reusable skills — when the user asks you to create/save a skill, " +
            "call skill_manager(action='create') with name, description, and content\n" +
            "- todo: manage persistent personal tasks, due dates, priorities, and completion\n" +
            "- bookmarks: save and retrieve links\n" +
            "- mood: log daily mood entries and review emotional patterns\n" +
            "- home_assistant: control smart home devices, inspect entity states, and list services via Home Assistant (action='list_entities', action='get_state', action='list_services', action='call_service')\n" +
            "- vision_analyze: analyze, describe, and extract text or details from images given an image path, URI, or URL (image_path, optional prompt)\n" +
            "- kanban: manage persistent project tickets on the Kanban board (action='create', action='create_batch' with tickets array to decompose complex requests into Kanban tickets, action='list', action='move', action='get', action='delete')\n" +
            "- clarify: ask the user a question (with optional choices) when a request is " +
            "ambiguous — prefer asking once over guessing\n" +
            "- delegate: hand focused or parallel subtasks to isolated subagents, get results back; " +
            "pass background=true for long tasks — the user is notified when done\n" +
            "- speak: read text aloud through the device speaker\n" +
            "- generate_image: create an image from a text prompt and return its URL\n\n" +
            "When a tool fits the request, call it — don't just describe what you could do.\n\n" +
            "Any personal info the user mentions (name, preferences, habits) — save it " +
            "with memory(action='add') immediately. Known context about the user is injected " +
            "at the start of every conversation — use it naturally, do not say you 'don't have memory'.\n\n" +
            "Keep replies concise (2–4 sentences) unless depth is requested. " +
            "If a task needs web search or scheduling, do it — don't just describe what you could do."

    override fun availableTools(registry: ToolRegistry): List<ToolDescriptor> =
        registry.toolsFor(role)
}
