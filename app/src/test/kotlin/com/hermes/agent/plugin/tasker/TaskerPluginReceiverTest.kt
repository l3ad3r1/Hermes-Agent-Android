package com.hermes.agent.plugin.tasker

import com.hermes.agent.data.voice.VoiceOutputManager
import com.hermes.agent.domain.agent.ExecutionOrigin
import com.hermes.agent.domain.agent.Orchestrator
import com.hermes.agent.domain.agent.OrchestratorEvent
import com.hermes.agent.domain.model.AgentRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskerPluginReceiverTest {

    private val orchestrator = mockk<Orchestrator>(relaxed = true)
    private val voiceOutputManager = mockk<VoiceOutputManager>(relaxed = true)

    private lateinit var receiver: TaskerPluginReceiver

    @Before
    fun setup() {
        receiver = TaskerPluginReceiver().apply {
            this.orchestrator = this@TaskerPluginReceiverTest.orchestrator
            this.voiceOutputManager = this@TaskerPluginReceiverTest.voiceOutputManager
        }
    }

    @Test
    fun `executeTask runs orchestrator and speaks response when enabled`() = runTest {
        val config = TaskerBundleHelper.TaskerConfig(
            agentRole = AgentRole.CONVERSATIONAL,
            promptTemplate = "Tell me a joke",
            speakResponse = true,
            timeoutSeconds = 10,
        )

        every {
            orchestrator.run(any(), "Tell me a joke", emptyList(), ExecutionOrigin.BACKGROUND)
        } returns flowOf(
            OrchestratorEvent.ReplyComplete("Why did the chicken cross the road?", AgentRole.CONVERSATIONAL, false),
        )

        val (response, exitCode) = receiver.executeTask(config)

        assertEquals("Why did the chicken cross the road?", response)
        assertEquals(0, exitCode)

        verify(atLeast = 1) {
            orchestrator.run(any(), "Tell me a joke", emptyList(), ExecutionOrigin.BACKGROUND)
        }
        verify(atLeast = 1) {
            voiceOutputManager.speak(eq("Why did the chicken cross the road?"), any())
        }
    }

    @Test
    fun `executeTask handles failed orchestrator outcome`() = runTest {
        val config = TaskerBundleHelper.TaskerConfig(
            agentRole = AgentRole.DEVICE_CONTROL,
            promptTemplate = "Turn on flight mode",
            speakResponse = false,
            timeoutSeconds = 10,
        )

        every {
            orchestrator.run(any(), "Turn on flight mode", emptyList(), ExecutionOrigin.BACKGROUND)
        } returns flowOf(
            OrchestratorEvent.Failed("Permission denied"),
        )

        val (response, exitCode) = receiver.executeTask(config)

        assertEquals("Permission denied", response)
        assertEquals(1, exitCode)

        verify(exactly = 0) {
            voiceOutputManager.speak(any())
        }
    }
}
