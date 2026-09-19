package com.hermes.agent.tool

import com.hermes.agent.data.local.LocalBotStore
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
 * Creates, lists, and removes this phone's local (on-device) bots.
 *
 * Not granted to any [com.hermes.agent.data.agent.agents.AgentToolAccess] role — its
 * category/capability ("bot_management") appears in no role grant, so it is invisible to
 * every ordinary conversation. [com.hermes.agent.data.agent.OrchestratorImpl] adds it back
 * in by hand for exactly one conversation: the local bot flagged
 * [com.hermes.agent.data.local.LocalBot.isChiefOfBots], and the Chief of Bots' own thread. Scoping it this narrowly — rather
 * than granting it broadly — is deliberate: a small on-device model can hallucinate tool
 * calls it was never asked to make, and a hallucinated "create/remove bot" call is a worse
 * outcome to risk than a hallucinated read-only one.
 */
@Singleton
class ManageLocalBotsTool @Inject constructor(
    private val localBotStore: LocalBotStore,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "manage_bots",
        description = "Create, list, or remove this phone's local on-device bots (named personas, " +
            "each with their own chat and system prompt). Use this when the user asks you to set up, " +
            "organize, or clean up their bots.",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "create, list or remove.",
                required = true,
                enumValues = listOf("create", "list", "remove"),
            ),
            ToolParameter(
                name = "name",
                type = ToolParameterType.STRING,
                description = "Bot name (required for create and remove).",
            ),
            ToolParameter(
                name = "system_prompt",
                type = ToolParameterType.STRING,
                description = "The new bot's persona/system prompt (required for create).",
            ),
        ),
        category = "bot_management",
        capabilities = setOf("bot_management"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start
        return when (arguments.string("action")?.lowercase()) {
            "list" -> ToolResult.ok(formatBots(), elapsed())
            "create" -> {
                // Small models often reach for kanban-style names (title/description) instead
                // of the schema's, so accept those rather than failing a request that is clear.
                val name = arguments.string("name") ?: arguments.string("title")
                    ?: return ToolResult.error("'create' needs a name.", elapsed())
                val prompt = arguments.string("system_prompt")
                    ?: arguments.string("prompt")
                    ?: arguments.string("persona")
                    ?: arguments.string("instructions")
                    ?: arguments.string("description")?.let { "You are $name. Your job: $it" }
                    ?: return ToolResult.error(
                        "'create' needs a system_prompt: a sentence or two describing the bot's persona.",
                        elapsed(),
                    )
                val bot = localBotStore.add(name, prompt)
                    ?: return ToolResult.error("'$name' is not a usable bot name (blank, or already taken).", elapsed())
                ToolResult.ok(
                    "Created local bot '${bot.name}'. It is ready in the Bots list. Do not create it again; " +
                        "just tell the user it is done.",
                    elapsed(),
                )
            }
            "remove" -> {
                val name = arguments.string("name")
                    ?: return ToolResult.error("'remove' needs a name.", elapsed())
                val bot = localBotStore.bots.value.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: return ToolResult.error("No local bot named '$name'.", elapsed())
                localBotStore.remove(bot.id)
                ToolResult.ok("Removed local bot '${bot.name}'. Just tell the user it is done.", elapsed())
            }
            else -> ToolResult.error("Unknown action. Expected create, list or remove.", elapsed())
        }
    }

    private fun formatBots(): String {
        val bots = localBotStore.bots.value
        if (bots.isEmpty()) return "No local bots exist yet."
        return "Local bots:\n" +
            bots.joinToString("\n") { "- ${it.name}" + if (it.isChiefOfBots) " (Chief of Bots)" else "" } +
            "\n\nThat is the complete list. Reply to the user with it now; do not call manage_bots again."
    }

    private fun Map<String, JsonElement>.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ManageLocalBotsToolModule {
    @Binds
    @IntoSet
    abstract fun bindManageLocalBotsTool(tool: ManageLocalBotsTool): Tool
}
