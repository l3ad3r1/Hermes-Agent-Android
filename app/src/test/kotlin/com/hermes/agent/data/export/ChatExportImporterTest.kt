package com.hermes.agent.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChatExportImporterTest {

    // n1 (root, no message) -> n2 user -> n3 assistant (abandoned branch)
    //                                 \-> n4 assistant (visible) -> n5 user (current_node)
    private val chatGpt = """
      [{"id":"c1","title":"Trip","create_time":1700000000.5,"update_time":1700000100,"current_node":"n5",
        "mapping":{
          "n1":{"id":"n1","parent":null,"children":["n2"],"message":null},
          "n2":{"id":"n2","parent":"n1","children":["n3","n4"],"message":{"id":"m2","author":{"role":"user"},"create_time":1700000001,"content":{"content_type":"text","parts":["Plan a trip"]}}},
          "n3":{"id":"n3","parent":"n2","children":[],"message":{"id":"m3","author":{"role":"assistant"},"create_time":1700000002,"content":{"parts":["Old branch"]}}},
          "n4":{"id":"n4","parent":"n2","children":["n5"],"message":{"id":"m4","author":{"role":"assistant"},"create_time":1700000003,"content":{"parts":["Visible answer"]}}},
          "n5":{"id":"n5","parent":"n4","children":[],"message":{"id":"m5","author":{"role":"user"},"create_time":1700000004,"content":{"parts":["Thanks",{"asset":"image"}]}}}
        }},
       {"id":"c2","title":"Only system","mapping":{"a":{"id":"a","parent":null,"children":[],"message":{"id":"x","author":{"role":"system"},"content":{"parts":["hidden"]}}}},"current_node":"a"}]
    """.trimIndent()

    private val claude = """
      [{"uuid":"u1","name":"","created_at":"2025-01-02T03:04:05.000Z","updated_at":"2025-01-02T03:05:00.000Z",
        "chat_messages":[
          {"uuid":"a","sender":"human","text":"Hello there","created_at":"2025-01-02T03:04:05.000Z"},
          {"uuid":"b","sender":"assistant","text":"old","content":[{"type":"text","text":"Hi!"},{"type":"tool_use","name":"x"}],"created_at":"2025-01-02T03:04:09.000Z"}]},
       {"uuid":"u2","name":"Empty","chat_messages":[]}]
    """.trimIndent()

    @Test
    fun `chatgpt follows the visible branch and drops system turns`() {
        val parsed = ChatExportImporter.parse(chatGpt.toByteArray())
        assertEquals(ChatExportImporter.Source.CHATGPT, parsed.source)
        assertEquals(1, parsed.skippedEmpty)
        val chat = parsed.conversations.single()
        assertEquals("chatgpt-c1", chat.id)
        assertEquals("Trip", chat.title)
        assertEquals(listOf("Plan a trip", "Visible answer", "Thanks"), chat.messages.map { it.content })
        assertEquals(listOf("user", "assistant", "user"), chat.messages.map { it.role })
        assertEquals(1700000000500L, chat.createdAt)
        assertEquals("Thanks", chat.lastMessagePreview)
    }

    @Test
    fun `claude reads content blocks over legacy text and titles a blank chat from its first message`() {
        val parsed = ChatExportImporter.parse(claude.toByteArray())
        assertEquals(ChatExportImporter.Source.CLAUDE, parsed.source)
        assertEquals(1, parsed.skippedEmpty)
        val chat = parsed.conversations.single()
        assertEquals("claude-u1", chat.id)
        assertEquals("Hello there", chat.title)
        assertEquals(listOf("Hello there", "Hi!"), chat.messages.map { it.content })
        assertEquals(listOf("user", "assistant"), chat.messages.map { it.role })
        assertTrue(chat.messages.all { it.timestamp > 0 })
    }

    @Test
    fun `a zip is unpacked to its conversations json`() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("export/user.json")); z.write("{}".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("export/conversations.json")); z.write(chatGpt.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        assertEquals("chatgpt-c1", ChatExportImporter.parse(zip).conversations.single().id)
    }

    @Test
    fun `ids are stable so importing twice adds nothing new`() {
        val a = ChatExportImporter.parse(chatGpt.toByteArray()).conversations.single()
        val b = ChatExportImporter.parse(chatGpt.toByteArray()).conversations.single()
        assertEquals(a.messages.map { it.id }, b.messages.map { it.id })
    }

    @Test
    fun `things that are not exports are refused with a reason`() {
        listOf("not json", "{}", """[{"foo":1}]""").forEach {
            try {
                ChatExportImporter.parse(it.toByteArray())
                fail("accepted: $it")
            } catch (e: ChatExportImporter.ImportFailure) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
        val emptyZip = ByteArrayOutputStream().also {
            ZipOutputStream(it).use { z -> z.putNextEntry(ZipEntry("a.txt")); z.closeEntry() }
        }.toByteArray()
        try {
            ChatExportImporter.parse(emptyZip)
            fail("accepted an empty zip")
        } catch (e: ChatExportImporter.ImportFailure) {
            assertTrue(e.message!!.contains("conversations.json"))
        }
    }
}
