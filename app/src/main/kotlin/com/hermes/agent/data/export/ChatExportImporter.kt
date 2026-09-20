package com.hermes.agent.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * Reads the "export your data" files from ChatGPT and Claude into Hermes chats.
 *
 * Both are a `conversations.json` (inside a zip with other files, or bare). This takes the bytes of
 * either the zip or the JSON and works out which service wrote it from the shape of the data, so
 * the caller does not have to ask.
 *
 * ChatGPT stores each chat as a tree of nodes, because you can edit a message and get a second
 * branch. Hermes chats are a line, so the branch that was on screen when the export was made
 * (`current_node` up to the root) is the one imported. Only the visible text of user and assistant
 * turns comes across: system prompts, tool calls, images and attachments do not, since a file
 * reference from another service means nothing here.
 *
 * Ids are derived from the service's own ids, so importing the same export twice adds nothing.
 */
object ChatExportImporter {

    enum class Source(val label: String) { CHATGPT("ChatGPT"), CLAUDE("Claude") }

    class ImportFailure(message: String) : Exception(message)

    data class Parsed(val source: Source, val conversations: List<ConversationBackup>, val skippedEmpty: Int)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray): Parsed {
        val text = if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            conversationsJsonFrom(bytes)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        val root = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: Exception) {
            throw ImportFailure("That file is not valid JSON, so it is not a ChatGPT or Claude export.")
        }
        val chats = root as? JsonArray
            ?: throw ImportFailure("Expected a list of conversations. This does not look like a ChatGPT or Claude export.")
        val first = chats.firstOrNull() as? JsonObject
        return when {
            first == null -> Parsed(Source.CHATGPT, emptyList(), 0)
            "mapping" in first -> fromChatGpt(chats)
            "chat_messages" in first -> fromClaude(chats)
            else -> throw ImportFailure("This is a list of conversations, but not in ChatGPT or Claude export format.")
        }
    }

    private fun conversationsJsonFrom(zip: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == "conversations.json") {
                    return String(z.readBytes(), Charsets.UTF_8)
                }
            }
        }
        throw ImportFailure("There is no conversations.json inside that zip.")
    }

    private fun fromChatGpt(chats: JsonArray): Parsed {
        var empty = 0
        val out = chats.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val id = c.str("conversation_id") ?: c.str("id") ?: return@mapNotNull null
            val mapping = c["mapping"] as? JsonObject ?: return@mapNotNull null
            // Walk from the visible leaf up to the root, then reverse it into reading order.
            val path = ArrayList<JsonObject>()
            var cursor: String? = c.str("current_node") ?: mapping.keys.firstOrNull { k ->
                ((mapping[k] as? JsonObject)?.get("children") as? JsonArray)?.isEmpty() == true
            }
            val seen = HashSet<String>()
            while (cursor != null && seen.add(cursor)) {
                val node = mapping[cursor] as? JsonObject ?: break
                path += node
                cursor = node.str("parent")
            }
            path.reverse()
            val messages = path.mapNotNull { node ->
                val m = node["message"] as? JsonObject ?: return@mapNotNull null
                val role = (m["author"] as? JsonObject)?.str("role") ?: return@mapNotNull null
                if (role != "user" && role != "assistant") return@mapNotNull null
                val parts = (m["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return@mapNotNull null
                val body = parts.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .joinToString("\n\n").trim()
                if (body.isEmpty()) return@mapNotNull null
                MessageBackup(
                    id = "chatgpt-${node.str("id") ?: m.str("id") ?: return@mapNotNull null}",
                    role = role,
                    content = body,
                    timestamp = seconds(m["create_time"]),
                )
            }
            if (messages.isEmpty()) {
                empty++
                return@mapNotNull null
            }
            conversation("chatgpt-$id", c.str("title"), seconds(c["create_time"]), seconds(c["update_time"]), messages)
        }
        return Parsed(Source.CHATGPT, out, empty)
    }

    private fun fromClaude(chats: JsonArray): Parsed {
        var empty = 0
        val out = chats.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            val id = c.str("uuid") ?: return@mapNotNull null
            val messages = (c["chat_messages"] as? JsonArray).orEmpty().mapNotNull { mel ->
                val m = mel as? JsonObject ?: return@mapNotNull null
                val role = when (m.str("sender")) {
                    "human" -> "user"
                    "assistant" -> "assistant"
                    else -> return@mapNotNull null
                }
                // Newer exports put the text in `content` blocks; older ones only have `text`.
                val fromBlocks = (m["content"] as? JsonArray).orEmpty()
                    .mapNotNull { b -> (b as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }
                    .joinToString("\n\n")
                val body = fromBlocks.ifBlank { m.str("text").orEmpty() }.trim()
                if (body.isEmpty()) return@mapNotNull null
                MessageBackup(
                    id = "claude-${m.str("uuid") ?: return@mapNotNull null}",
                    role = role,
                    content = body,
                    timestamp = iso(m.str("created_at")),
                )
            }
            if (messages.isEmpty()) {
                empty++
                return@mapNotNull null
            }
            conversation("claude-$id", c.str("name"), iso(c.str("created_at")), iso(c.str("updated_at")), messages)
        }
        return Parsed(Source.CLAUDE, out, empty)
    }

    private fun conversation(
        id: String,
        title: String?,
        created: Long,
        updated: Long,
        messages: List<MessageBackup>,
    ): ConversationBackup {
        val first = messages.firstOrNull { it.role == "user" }?.content ?: messages.first().content
        val start = created.takeIf { it > 0 } ?: messages.first().timestamp
        val end = updated.takeIf { it > 0 } ?: messages.last().timestamp
        return ConversationBackup(
            id = id,
            title = title?.trim().orEmpty().ifBlank { first.lineSequence().first().take(60) },
            createdAt = start,
            updatedAt = end,
            lastMessagePreview = messages.last().content.take(120),
            messages = messages,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun seconds(el: JsonElement?): Long =
        ((el as? JsonPrimitive)?.doubleOrNull)?.let { (it * 1000).toLong() } ?: 0L

    private fun iso(text: String?): Long =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}
