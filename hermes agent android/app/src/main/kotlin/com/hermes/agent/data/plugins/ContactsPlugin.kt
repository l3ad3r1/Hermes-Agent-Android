package com.hermes.agent.data.plugins

import android.content.Context
import android.provider.ContactsContract
import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contacts plugin — Phase 3 sample.
 *
 * Advertises two tools:
 *   - `contacts_search` — fuzzy-search the user's contacts by name.
 *   - `contacts_get`    — fetch full details (phone + email) for a contact.
 *
 * Reads via [ContactsContract] with READ_CONTACTS permission. The host
 * app's manifest declares the permission; the user grants it at runtime
 * on first tool invocation.
 *
 * The plugin deliberately does NOT expose a `contacts_write` tool in
 * Phase 3 — write access is staged for Phase 4 once we have proper
 * per-plugin confirmation UX in place.
 */
@Singleton
class ContactsPlugin @Inject constructor(
    @ApplicationContext private val context: Context,
) : Plugin {

    override val manifest: PluginManifest by lazy {
        PluginManifest(
        id = "com.hermes.plugin.contacts",
        displayName = "Contacts",
        versionCode = 1,
        versionName = "1.0.0",
        author = "Hermes Team",
        signatureFingerprint = "(in-process, no signature required)",
        capabilities = listOf(
            PluginCapability(
                name = "contacts_search",
                description = "Search the user's contacts by name.",
                toolDescriptors = listOf(searchDescriptor),
            ),
            PluginCapability(
                name = "contacts_get",
                description = "Get phone + email for a single contact.",
                toolDescriptors = listOf(getDescriptor),
            ),
        ),
        permissions = listOf(
            PluginPermission(
                type = PermissionType.CONTACTS,
                rationale = "Reads contact names, phone numbers, and email addresses.",
            ),
        ),
        )
    }

    override fun tools(): List<Tool> = listOf(searchTool, getTool)

    override suspend fun onLoad(context: PluginContext): PluginLifecycleResult {
        context.log("Contacts", com.hermes.agent.domain.plugin.LogLevel.INFO, "loading")
        return PluginLifecycleResult.Success
    }

    override suspend fun onSuspend(): PluginLifecycleResult = PluginLifecycleResult.Success
    override suspend fun onResume(): PluginLifecycleResult = PluginLifecycleResult.Success
    override suspend fun onUnload(): PluginLifecycleResult = PluginLifecycleResult.Success

    private val searchDescriptor = ToolDescriptor(
        name = "contacts_search",
        description = "Search the user's contacts by name (case-insensitive substring match).",
        parameters = listOf(
            ToolParameter(
                name = "query",
                type = ToolParameterType.STRING,
                description = "Name or partial name to search for.",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of contacts to return. Defaults to 10.",
                required = false,
            ),
        ),
        category = "communication",
    )

    private val getDescriptor = ToolDescriptor(
        name = "contacts_get",
        description = "Fetch phone numbers and email addresses for a single contact by id.",
        parameters = listOf(
            ToolParameter(
                name = "contact_id",
                type = ToolParameterType.STRING,
                description = "The contact id returned by contacts_search.",
            ),
        ),
        category = "communication",
    )

    private val searchTool = object : Tool {
        override val descriptor = searchDescriptor

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
            val query = arguments["query"]?.extractString()
                ?: return ToolResult.error("missing required parameter: query")
            val limit = arguments["limit"]?.extractString()?.toIntOrNull() ?: 10

            val matches = mutableListOf<String>()
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            )
            val selection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$query%")

            return runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    projection, selection, args, null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && matches.size < limit) {
                        val id = cursor.getString(0)
                        val name = cursor.getString(1)
                        matches.add("[$id] $name")
                    }
                }
                if (matches.isEmpty()) ToolResult.ok("no contacts matched \"$query\"")
                else ToolResult.ok(matches.joinToString("\n"))
            }.getOrElse { ToolResult.error(it.message ?: "contacts_search failed") }
        }
    }

    private val getTool = object : Tool {
        override val descriptor = getDescriptor

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
            val contactId = arguments["contact_id"]?.extractString()
                ?: return ToolResult.error("missing required parameter: contact_id")

            return runCatching {
                val phones = mutableListOf<String>()
                val emails = mutableListOf<String>()

                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId), null,
                )?.use { c ->
                    while (c.moveToNext()) phones.add(c.getString(0) ?: "")
                }

                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(contactId), null,
                )?.use { c ->
                    while (c.moveToNext()) emails.add(c.getString(0) ?: "")
                }

                buildString {
                    append("contact_id=$contactId\n")
                    append("phones: ").append(phones.ifEmpty { listOf("(none)") }.joinToString(", ")).append('\n')
                    append("emails: ").append(emails.ifEmpty { listOf("(none)") }.joinToString(", "))
                }.let { ToolResult.ok(it) }
            }.getOrElse { ToolResult.error(it.message ?: "contacts_get failed") }
        }
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
