package com.hermes.agent.ui.chat

import com.hermes.agent.ui.chat.components.ArtifactExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactExtractorTest {

    @Test
    fun testExtractHtmlArtifact() {
        val markdown = """
            Here is the web mockup:
            ```html
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><h1>Hello World</h1></body>
            </html>
            ```
            Let me know what you think.
        """.trimIndent()

        val artifacts = ArtifactExtractor.extractArtifacts(markdown)
        assertEquals(1, artifacts.size)
        assertEquals("html", artifacts[0].language)
        assertEquals("Web Page Preview", artifacts[0].title)
        assertTrue(artifacts[0].code.contains("<h1>Hello World</h1>"))
    }

    @Test
    fun testExtractMultipleArtifacts() {
        val markdown = """
            First, here is the Kotlin model:
            ```kotlin
            data class User(val id: String, val name: String)
            ```
            And here is the JSON payload:
            ```json
            {"id": "u1", "name": "Alice"}
            ```
        """.trimIndent()

        val artifacts = ArtifactExtractor.extractArtifacts(markdown)
        assertEquals(2, artifacts.size)
        assertEquals("kotlin", artifacts[0].language)
        assertEquals("Kotlin Source", artifacts[0].title)
        assertEquals("json", artifacts[1].language)
        assertEquals("JSON Schema", artifacts[1].title)
    }

    @Test
    fun testNoArtifactsInPlainText() {
        val markdown = "Hello, this is just a regular message with no code fences."
        val artifacts = ArtifactExtractor.extractArtifacts(markdown)
        assertTrue(artifacts.isEmpty())
    }
}
