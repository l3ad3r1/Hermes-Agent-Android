package com.hermes.agent.plugin.tasker

import android.content.Intent
import android.os.Bundle
import com.hermes.agent.domain.model.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskerBundleHelperTest {

    @Test
    fun `roundtrip serialization to bundle and from bundle`() {
        val config = TaskerBundleHelper.TaskerConfig(
            agentRole = AgentRole.DEVICE_CONTROL,
            promptTemplate = "Set brightness to 80% and speak 'Brightness updated'",
            timeoutSeconds = 45,
            speakResponse = true,
        )

        val bundle = config.toBundle()
        val restored = TaskerBundleHelper.fromBundle(bundle)

        assertEquals(AgentRole.DEVICE_CONTROL, restored.agentRole)
        assertEquals("Set brightness to 80% and speak 'Brightness updated'", restored.promptTemplate)
        assertEquals(45, restored.timeoutSeconds)
        assertTrue(restored.speakResponse)
    }

    @Test
    fun `extract from Intent with Extra Bundle`() {
        val config = TaskerBundleHelper.TaskerConfig(
            agentRole = AgentRole.PRODUCTIVITY,
            promptTemplate = "Check my calendar for meetings",
            timeoutSeconds = 30,
            speakResponse = false,
        )

        val intent = Intent().apply {
            putExtra(TaskerBundleHelper.EXTRA_BUNDLE, config.toBundle())
            putExtra(TaskerBundleHelper.EXTRA_BLURB, config.toBlurb())
        }

        val restored = TaskerBundleHelper.fromIntent(intent)
        assertEquals(AgentRole.PRODUCTIVITY, restored.agentRole)
        assertEquals("Check my calendar for meetings", restored.promptTemplate)
        assertEquals(30, restored.timeoutSeconds)
        assertFalse(restored.speakResponse)
    }

    @Test
    fun `blurb formatting is clear and truncated when long`() {
        val shortConfig = TaskerBundleHelper.TaskerConfig(
            agentRole = AgentRole.CONVERSATIONAL,
            promptTemplate = "What is the weather today?",
        )
        assertEquals("Conversational: \"What is the weather today?\"", shortConfig.toBlurb())

        val longConfig = TaskerBundleHelper.TaskerConfig(
            agentRole = AgentRole.DEVICE_CONTROL,
            promptTemplate = "This is a very long prompt template designed to exceed the maximum character limit for blurb display in Tasker",
        )
        assertTrue(longConfig.toBlurb().endsWith("...\""))
    }
}
