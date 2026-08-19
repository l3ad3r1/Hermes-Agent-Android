package com.hermes.agent.data.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.hermes.agent.domain.tool.*
import com.hermes.agent.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
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
class ContactLookupTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : Tool {
    override val descriptor = ToolDescriptor(
        name = "contact_lookup",
        description = "Find contacts by name and return their phone numbers.",
        parameters = listOf(ToolParameter("query", ToolParameterType.STRING, "Contact name to search for.")),
        category = "communication",
        capabilities = setOf("contacts", "communication"),
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(dispatchers.io) {
        val query = arguments.string("query") ?: return@withContext ToolResult.error("query is required")
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext ToolResult.error("Contacts permission is not granted")
        }
        runCatching {
            val rows = mutableListOf<String>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < 10) {
                    rows += "${cursor.getString(1)} — ${cursor.getString(2)} (id=${cursor.getLong(0)})"
                }
            }
            if (rows.isEmpty()) ToolResult.ok("No contacts found for $query")
            else ToolResult.ok(rows.joinToString("\n"))
        }.getOrElse { ToolResult.error("Contact lookup failed: ${it.message}") }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ContactLookupToolModule {
    @Binds
    @IntoSet
    abstract fun bindContactLookupTool(tool: ContactLookupTool): Tool
}
