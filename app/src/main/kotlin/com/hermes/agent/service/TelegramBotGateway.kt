package com.hermes.agent.service

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 24/7 Self-Hosted Telegram Gateway Bot.
 * Runs inside [AgentForegroundService] on the Android device, allowing users
 * to message their phone's Hermes agent directly via Telegram.
 */
@Singleton
class TelegramBotGateway @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val orchestrator: Orchestrator,
    private val okHttpClient: OkHttpClient,
) {
    private var pollJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val longPollClient = okHttpClient.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    fun start(scope: CoroutineScope) {
        pollJob?.cancel()
        pollJob = scope.launch {
            settingsRepository.observe().collectLatest { settings ->
                if (settings.telegramBotEnabled && settings.telegramBotToken.isNotBlank()) {
                    Timber.tag("TelegramGateway").i("Starting Telegram long-polling loop")
                    runPollingLoop(
                        token = settings.telegramBotToken,
                        allowedUserIds = settings.telegramAllowedUserIds
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toSet(),
                    )
                } else {
                    Timber.tag("TelegramGateway").d("Telegram bot disabled")
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun runPollingLoop(token: String, allowedUserIds: Set<String>) {
        var lastOffset = 0L
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                val url = "https://api.telegram.org/bot$token/getUpdates?offset=$lastOffset&timeout=25"
                val request = Request.Builder().url(url).get().build()

                val response = longPollClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    delay(5_000)
                    continue
                }

                val root = json.parseToJsonElement(body).jsonObject
                val ok = root["ok"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
                if (!ok) {
                    delay(10_000)
                    continue
                }

                val result = root["result"]?.jsonArray ?: JsonArray(emptyList())
                for (item in result) {
                    val updateObj = item.jsonObject
                    val updateId = updateObj["update_id"]?.jsonPrimitive?.long ?: continue
                    lastOffset = updateId + 1

                    val messageObj = updateObj["message"]?.jsonObject ?: continue
                    val messageText = messageObj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val fromObj = messageObj["from"]?.jsonObject
                    val fromId = fromObj?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val chatObj = messageObj["chat"]?.jsonObject
                    val chatId = chatObj?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()

                    if (chatId.isBlank() || messageText.isBlank()) continue

                    // Check whitelist
                    if (allowedUserIds.isNotEmpty() && fromId !in allowedUserIds) {
                        Timber.tag("TelegramGateway").w("Unauthorized Telegram user: $fromId")
                        sendReply(token, chatId, "⛔ Access denied: user ID $fromId is not authorized on this Hermes device.")
                        continue
                    }

                    // Process with Orchestrator
                    processAndReply(token, chatId, messageText)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.tag("TelegramGateway").w(t, "Telegram polling error, retrying in 5s...")
                delay(5_000)
            }
        }
    }

    private suspend fun processAndReply(token: String, chatId: String, prompt: String) {
        val conversationId = "telegram-$chatId"
        val result = runCatching {
            val events = orchestrator.run(
                conversationId = conversationId,
                userMessage = prompt,
                recentMessages = emptyList(),
                origin = ExecutionOrigin.BACKGROUND,
            ).toList()

            events.filterIsInstance<OrchestratorEvent.ReplyComplete>()
                .firstOrNull()?.finalText
                ?: events.filterIsInstance<OrchestratorEvent.ReplyToken>()
                    .joinToString("") { it.text }
                    .ifBlank { "Done." }
        }.getOrElse { error ->
            "Hermes Error: ${error.message}"
        }

        sendReply(token, chatId, result)
    }

    private fun sendReply(token: String, chatId: String, replyText: String) {
        try {
            val url = "https://api.telegram.org/bot$token/sendMessage"
            val payload = JsonObject(
                mapOf(
                    "chat_id" to kotlinx.serialization.json.JsonPrimitive(chatId),
                    "text" to kotlinx.serialization.json.JsonPrimitive(replyText),
                ),
            ).toString()

            val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            longPollClient.newCall(request).execute().close()
        } catch (t: Throwable) {
            Timber.tag("TelegramGateway").e(t, "Failed to send Telegram reply to $chatId")
        }
    }
}
