package com.hermes.agent.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.hermes.agent.domain.tool.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import com.hermes.agent.domain.tool.Tool

@Singleton
class CommunicationTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Tool {
    override val descriptor = ToolDescriptor(
        name = "communication",
        description = "Open the dialer, compose an SMS or email, or open the add-contact screen. Does not place calls or send messages automatically.",
        parameters = listOf(
            ToolParameter("action", ToolParameterType.STRING, "Communication action.", enumValues = listOf("dial", "compose_sms", "compose_email", "add_contact")),
            ToolParameter("recipient", ToolParameterType.STRING, "Phone number, email address, or contact name.", required = false),
            ToolParameter("message", ToolParameterType.STRING, "SMS or email body.", required = false),
            ToolParameter("subject", ToolParameterType.STRING, "Email subject.", required = false),
            ToolParameter("phone", ToolParameterType.STRING, "Phone number for a new contact.", required = false),
            ToolParameter("email", ToolParameterType.STRING, "Email address for a new contact.", required = false),
        ),
        category = "communication",
        capabilities = setOf("contacts", "communication"),
        requiresConfirmation = true,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val action = arguments.string("action") ?: return ToolResult.error("missing required parameter: action")
        val recipient = arguments.string("recipient")
        val intent = when (action) {
            "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(recipient ?: return ToolResult.error("recipient is required"))}"))
            "compose_sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(recipient ?: return ToolResult.error("recipient is required"))}"))
                .putExtra("sms_body", arguments.string("message").orEmpty())
            "compose_email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(recipient ?: return ToolResult.error("recipient is required"))}"))
                .putExtra(Intent.EXTRA_SUBJECT, arguments.string("subject").orEmpty())
                .putExtra(Intent.EXTRA_TEXT, arguments.string("message").orEmpty())
            "add_contact" -> Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
                .putExtra(ContactsContract.Intents.Insert.NAME, recipient.orEmpty())
                .putExtra(ContactsContract.Intents.Insert.PHONE, arguments.string("phone").orEmpty())
                .putExtra(ContactsContract.Intents.Insert.EMAIL, arguments.string("email").orEmpty())
            else -> return ToolResult.error("unknown communication action: $action")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.ok("Opened $action")
        }.getOrElse { ToolResult.error("No compatible app is available: ${it.message}") }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CommunicationToolModule {
    @Binds
    @IntoSet
    abstract fun bindCommunicationTool(tool: CommunicationTool): Tool
}
