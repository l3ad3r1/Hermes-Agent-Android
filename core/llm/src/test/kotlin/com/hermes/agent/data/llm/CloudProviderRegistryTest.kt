package com.hermes.agent.data.llm
import com.hermes.agent.domain.llm.*
import com.hermes.agent.domain.settings.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudProviderRegistryTest {

    @Test
    fun `available saved best model stays preselected`() {
        val groq = requireNotNull(CloudProviderRegistry.definition("groq"))

        val ordered = CloudProviderRegistry.orderModels(
            definition = groq,
            available = listOf("llama-3.1-8b-instant", "openai/gpt-oss-120b"),
            savedModel = "openai/gpt-oss-120b",
        )

        assertEquals("openai/gpt-oss-120b", ordered.first())
    }

    @Test
    fun `curated default replaces unavailable saved model and non-chat models are hidden`() {
        val groq = requireNotNull(CloudProviderRegistry.definition("groq"))

        val ordered = CloudProviderRegistry.orderModels(
            definition = groq,
            available = listOf("whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-120b"),
            savedModel = "removed-model",
        )

        assertEquals("openai/gpt-oss-120b", ordered.first())
        assertFalse(ordered.contains("whisper-large-v3"))
    }
}
