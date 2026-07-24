package com.hermes.agent.data.plugins

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PermissionType
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
 * File Manager plugin — Phase 3 sample.
 *
 * Advertises two tools:
 *   - `file_list`   — list files in a directory tree.
 *   - `file_read`   — read up to 4 KB of a text file.
 *
 * Uses the Android Storage Access Framework (SAF) via DocumentsContract.
 * Reads are constrained to text MIME types (text/plain, text/markdown, etc.)
 * so we never accidentally slurp binary blobs into the LLM context.
 *
 * Permissions: STORAGE (broad filesystem access via SAF).
 *
 * Security note: The plugin never opens arbitrary file paths — it only
 * resolves URIs the user has previously granted via the SAF document
 * tree picker. This is the privacy-first pattern called out in
 * Section 2.3 of the plan.
 */
@Singleton
class FileManagerPlugin @Inject constructor(
    @ApplicationContext private val context: Context,
) : Plugin {

    private val fileListDescriptor = ToolDescriptor(
        name = "file_list",
        description = "List the files in a directory the user has granted access to via the Storage Access Framework.",
        parameters = listOf(
            ToolParameter(
                name = "tree_uri",
                type = ToolParameterType.STRING,
                description = "The SAF tree URI the user granted. The host app must hold a persisted " +
                    "permission for this URI.",
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of entries to return. Defaults to 50.",
                required = false,
            ),
        ),
        category = "device",
    )

    private val fileReadDescriptor = ToolDescriptor(
        name = "file_read",
        description = "Read up to 4 KB of a text file by URI.",
        parameters = listOf(
            ToolParameter(
                name = "file_uri",
                type = ToolParameterType.STRING,
                description = "The SAF document URI of the file to read.",
            ),
        ),
        category = "device",
    )

    override val manifest = PluginManifest(
        id = "com.hermes.plugin.filemanager",
        displayName = "File Manager",
        versionCode = 1,
        versionName = "1.0.0",
        author = "Jeeves Team",
        signatureFingerprint = "(in-process, no signature required)",
        capabilities = listOf(
            PluginCapability(
                name = "file_list",
                description = "List files in a SAF-granted directory.",
                toolDescriptors = listOf(fileListDescriptor),
            ),
            PluginCapability(
                name = "file_read",
                description = "Read up to 4 KB of a text file.",
                toolDescriptors = listOf(fileReadDescriptor),
            ),
        ),
        permissions = listOf(
            PluginPermission(
                type = PermissionType.STORAGE,
                rationale = "Reads files from directories the user has granted via the Storage Access Framework.",
            ),
        ),
    )

    override fun tools(): List<Tool> = listOf(fileListTool, fileReadTool)

    override suspend fun onLoad(context: PluginContext): PluginLifecycleResult {
        context.log("FileManager", com.hermes.agent.domain.plugin.LogLevel.INFO, "loading")
        return PluginLifecycleResult.Success
    }

    override suspend fun onSuspend(): PluginLifecycleResult = PluginLifecycleResult.Success
    override suspend fun onResume(): PluginLifecycleResult = PluginLifecycleResult.Success
    override suspend fun onUnload(): PluginLifecycleResult = PluginLifecycleResult.Success

    private val fileListTool = object : Tool {
        override val descriptor = fileListDescriptor

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
            val treeUriStr = arguments["tree_uri"]?.extractString()
                ?: return ToolResult.error("missing required parameter: tree_uri")
            val limit = arguments["limit"]?.extractString()?.toIntOrNull() ?: 50

            return runCatching {
                val treeUri = Uri.parse(treeUriStr)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
                val out = mutableListOf<String>()
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                )?.use { cursor ->
                    while (cursor.moveToNext() && out.size < limit) {
                        val name = cursor.getString(1)
                        val mime = cursor.getString(2)
                        val size = cursor.getLong(3)
                        val type = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) "[dir]" else "[file]"
                        out.add("$type $name (${size} bytes)")
                    }
                }
                if (out.isEmpty()) ToolResult.ok("directory is empty or inaccessible")
                else ToolResult.ok(out.joinToString("\n"))
            }.getOrElse { ToolResult.error(it.message ?: "file_list failed") }
        }
    }

    private val fileReadTool = object : Tool {
        override val descriptor = fileReadDescriptor

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
            val fileUriStr = arguments["file_uri"]?.extractString()
                ?: return ToolResult.error("missing required parameter: file_uri")

            return runCatching {
                val uri = Uri.parse(fileUriStr)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                if (!mime.startsWith("text/")) {
                    return@runCatching ToolResult.error("refuse to read non-text file (mime=$mime)")
                }
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val capped = if (bytes.size > MAX_READ_BYTES) {
                        bytes.copyOf(MAX_READ_BYTES)
                    } else bytes
                    String(capped, Charsets.UTF_8)
                } ?: return@runCatching ToolResult.error("could not open URI")

                ToolResult.ok(text)
            }.getOrElse { ToolResult.error(it.message ?: "file_read failed") }
        }
    }

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_READ_BYTES = 4 * 1024
    }
}
