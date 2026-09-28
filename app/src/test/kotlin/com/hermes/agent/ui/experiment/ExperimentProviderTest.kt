package com.hermes.agent.ui.experiment

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.hermes.agent.data.llm.CloudLlmProvider
import com.hermes.agent.data.llm.ProfileCloudProviderFactory
import com.hermes.agent.domain.llm.LlmStreamChunk
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentProviderTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a model served by a provider profile runs through that provider`() = runTest(dispatcher) {
        // On the tablet the relay (a provider profile) failed with "cloud API key not set".
        val relay = CloudProviderProfile(
            id = "custom_relay", name = "Antigravity", baseUrl = "http://relay/v1", model = "hermes-relay",
            apiKey = "k", enabled = true, quality = 0.9, cost = 0.1, latency = 0.5, toolReliability = 0.9,
        )
        val settings = UserSettings(cloudProviderProfiles = listOf(relay))
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { observe() } returns MutableStateFlow(settings)
            coEvery { current() } returns settings
        }
        val relayProvider = mockk<CloudLlmProvider> {
            every { stream(any()) } returns flowOf(LlmStreamChunk.Delta("Mars"), LlmStreamChunk.Done)
        }
        val bare = mockk<CloudLlmProvider>(relaxed = true)
        val factory = mockk<ProfileCloudProviderFactory> { every { create(relay) } returns relayProvider }
        val viewModel = ExperimentViewModel(bare, mockk(relaxed = true), settingsRepository, factory)
        advanceUntilIdle()

        assertEquals("hermes-relay", viewModel.state.value.modelA)
        viewModel.setPrompt("Name a planet")
        viewModel.setModelB("hermes-relay")
        viewModel.run()
        advanceUntilIdle()

        assertEquals("Mars", viewModel.state.value.responseA)
        assertNull(viewModel.state.value.errorA)
        verify(exactly = 0) { bare.streamWithModelOverride(any(), any()) }
    }
}
