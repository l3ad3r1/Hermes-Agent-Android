package com.hermes.agent.data.tools

import com.hermes.agent.domain.repository.ConnectorRepository
import com.hermes.agent.domain.model.ConnectorType
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a notification to all enabled connectors (Webhook, Telegram, Discord).
 * Also usable by the LLM as a tool to push messages to external platforms.
 */
@Singleton
class WebhookTool @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val connectorRepository: ConnectorRepository,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "notify",
        description = "Send a message to connected platforms (Telegram, Discord, webhook). Use when you need to notify the user via an external channel.",
        parameters = listOf(
            ToolParameter("message", ToolParameterType.STRING, "The message to send."),
            ToolParameter("platform", ToolParameterType.STRING,
                "Optional: specific platform name to target (e.g. 'Telegram'). Omit to send to all enabled connectors.",
                required = false),
        ),
        category = "communication",
    )

    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val message = (arguments["message"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.error("missing required parameter: message")
        val platform = (arguments["platform"] as? JsonPrimitive)?.contentOrNull

        val connectors = connectorRepository.getEnabled().filter { c ->
            platform == null || c.type.displayName.equals(platform, ignoreCase = true) || c.name.equals(platform, ignoreCase = true)
        }

        if (connectors.isEmpty()) return ToolResult.ok("No connectors enabled.", System.currentTimeMillis() - start)

        var sent = 0
        connectors.forEach { connector ->
            runCatching {
                when (connector.type) {
                    ConnectorType.WEBHOOK -> postWebhook(connector.config["url"] ?: return@forEach, message)
                    ConnectorType.TELEGRAM -> postTelegram(
                        connector.config["botToken"] ?: return@forEach,
                        connector.config["chatId"] ?: return@forEach,
                        message,
                    )
                    ConnectorType.DISCORD -> postDiscord(connector.config["url"] ?: return@forEach, message)
                }
                connectorRepository.recordUsed(connector.id)
                sent++
            }.onFailure { e -> Timber.e(e, "WebhookTool: failed to send via ${connector.name}") }
        }

        return ToolResult.ok("Sent to $sent connector(s).", System.currentTimeMillis() - start)
    }

    private fun postWebhook(url: String, message: String) {
        val body = """{"text":${JsonPrimitive(message)}}""".toRequestBody(json)
        okHttpClient.newCall(Request.Builder().url(url).post(body).build()).execute().close()
    }

    private fun postTelegram(botToken: String, chatId: String, message: String) {
        val body = """{"chat_id":${JsonPrimitive(chatId)},"text":${JsonPrimitive(message)}}""".toRequestBody(json)
        okHttpClient.newCall(
            Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(body)
                .build()
        ).execute().close()
    }

    private fun postDiscord(webhookUrl: String, message: String) {
        val body = """{"content":${JsonPrimitive(message)}}""".toRequestBody(json)
        okHttpClient.newCall(Request.Builder().url(webhookUrl).post(body).build()).execute().close()
    }
}
