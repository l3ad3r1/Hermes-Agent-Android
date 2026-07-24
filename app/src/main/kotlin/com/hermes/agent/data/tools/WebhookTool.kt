package com.hermes.agent.data.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.hermes.agent.data.security.WebhookSigner
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
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "notify",
        description = "Send a message to connected platforms (Telegram, Discord, Signal, WhatsApp, webhook). Use when you need to notify the user via an external channel.",
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
                    ConnectorType.WEBHOOK -> postWebhook(
                        connector.config["url"] ?: return@forEach,
                        message,
                        connector.config["secret"],
                    )
                    ConnectorType.TELEGRAM -> postTelegram(
                        connector.config["botToken"] ?: return@forEach,
                        connector.config["chatId"] ?: return@forEach,
                        message,
                    )
                    ConnectorType.DISCORD -> postDiscord(connector.config["url"] ?: return@forEach, message)
                    ConnectorType.SIGNAL -> postSignal(
                        connector.config["url"] ?: return@forEach,
                        connector.config["recipient"] ?: return@forEach,
                        message,
                    )
                    ConnectorType.WHATSAPP -> postWhatsApp(
                        connector.config["phoneNumberId"] ?: return@forEach,
                        connector.config["accessToken"] ?: return@forEach,
                        connector.config["recipient"] ?: return@forEach,
                        message,
                    )
                    ConnectorType.SMS -> sendSms(
                        connector.config["recipient"] ?: connector.config["phoneNumber"] ?: return@forEach,
                        message,
                    )
                }
                connectorRepository.recordUsed(connector.id)
                sent++
            }.onFailure { e -> Timber.e(e, "WebhookTool: failed to send via ${connector.name}") }
        }

        if (platform == null || platform.equals("local", ignoreCase = true)) {
            postLocalNotification(message)
            sent++
        }

        return ToolResult.ok("Sent to $sent connector(s).", System.currentTimeMillis() - start)
    }

    private fun postLocalNotification(message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel("jeeves_notify", "Jeeves Notifications", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        val remoteInput = androidx.core.app.RemoteInput.Builder("KEY_REPLY")
            .setLabel("Reply to Jeeves...")
            .build()

        val replyIntent = android.content.Intent(context, com.hermes.agent.receiver.NotificationReplyReceiver::class.java).apply {
            action = "com.hermes.agent.action.REPLY"
        }
        val replyPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        val action = androidx.core.app.NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = androidx.core.app.NotificationCompat.Builder(context, "jeeves_notify")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Jeeves")
            .setContentText(message)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
            .addAction(action)
            .setAutoCancel(true)
            .build()

        nm.notify(message.hashCode(), notification)
    }

    private fun postWebhook(url: String, message: String, secret: String?) {
        val payload = """{"text":${JsonPrimitive(message)}}"""
        val request = Request.Builder().url(url).post(payload.toRequestBody(json))
        // If the user configured a shared secret, HMAC-sign the body so the
        // receiver can authenticate the delivery (ported from hermes-agent's
        // connector channel auth). Other platforms use their own tokens.
        if (!secret.isNullOrBlank()) {
            val sig = WebhookSigner.sign(secret, payload)
            request.addHeader(WebhookSigner.HEADER_TIMESTAMP, sig.timestamp)
            request.addHeader(WebhookSigner.HEADER_SIGNATURE, sig.header)
        }
        okHttpClient.newCall(request.build()).execute().close()
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

    /** Signal via signal-cli REST API (https://github.com/bbernhard/signal-cli-rest-api). */
    private fun postSignal(apiUrl: String, recipient: String, message: String) {
        val body = """{"message":${JsonPrimitive(message)},"recipients":[${JsonPrimitive(recipient)}]}"""
            .toRequestBody(json)
        okHttpClient.newCall(
            Request.Builder().url("${apiUrl.trimEnd('/')}/v2/send").post(body).build()
        ).execute().close()
    }

    /** Native SMS via [SmsManager]. Requires the SEND_SMS runtime permission. */
    private fun sendSms(recipient: String, message: String) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("WebhookTool: SEND_SMS permission not granted; skipping SMS to %s", recipient)
            return
        }
        @Suppress("DEPRECATION")
        val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
    }

    /** WhatsApp Cloud API (Meta). phoneNumberId is the From number ID in the dashboard. */
    private fun postWhatsApp(phoneNumberId: String, accessToken: String, recipient: String, message: String) {
        val body = """{"messaging_product":"whatsapp","to":${JsonPrimitive(recipient)},"type":"text","text":{"body":${JsonPrimitive(message)}}}"""
            .toRequestBody(json)
        okHttpClient.newCall(
            Request.Builder()
                .url("https://graph.facebook.com/v18.0/$phoneNumberId/messages")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(body)
                .build()
        ).execute().close()
    }
}
