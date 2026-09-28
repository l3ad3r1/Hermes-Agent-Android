package com.hermes.agent.ui.settings

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelStore
import com.hermes.agent.domain.settings.CloudProviderProfile
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelProviderSaveTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an edit saved as the providers screen closes is not lost`() = runTest(dispatcher) {
        // The provider card saves its Base URL when it leaves composition. On the tablet
        // that happens as the screen's view model is cleared, and the save was cancelled
        // with it: a changed URL came back unchanged.
        val profile = CloudProviderProfile(
            id = "custom_test", name = "Test", baseUrl = "http://127.0.0.1:9/v1", model = "m",
            apiKey = "", enabled = true, quality = 0.5, cost = 0.1, latency = 0.5, toolReliability = 0.5,
        )
        val settingsFlow = MutableStateFlow(UserSettings(cloudProviderProfiles = listOf(profile)))
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { observe() } returns settingsFlow
            coEvery { current() } answers { settingsFlow.value }
        }
        val viewModel = settingsViewModel(settingsRepository)
        advanceUntilIdle()

        ViewModelStore().apply { put("settings", viewModel) }.clear()
        viewModel.setProviderBaseUrl("custom_test", "http://127.0.0.1:8/v1")
        advanceUntilIdle()

        coVerify {
            settingsRepository.setCloudProviderProfiles(
                match { profiles -> profiles.single().baseUrl == "http://127.0.0.1:8/v1" },
            )
        }
    }

    private fun settingsViewModel(settingsRepository: SettingsRepository) = SettingsViewModel(
        appContext = mockk<Context>(relaxed = true),
        settingsRepository = settingsRepository,
        keystore = mockk(relaxed = true),
        otaUpdateChecker = mockk(relaxed = true),
        otaInstaller = mockk(relaxed = true),
        sessionExporter = mockk(relaxed = true),
        jsonBackupManager = mockk(relaxed = true),
        botsBackup = mockk(relaxed = true),
        credentialVault = mockk(relaxed = true),
        privilegedShellBackend = mockk(relaxed = true),
        privilegedShellRetryGate = com.hermes.agent.data.device.PrivilegedShellRetryGate(),
        cloudModelCatalog = mockk(relaxed = true),
        localLlmManager = mockk(relaxed = true) {
            every { isDownloading } returns MutableStateFlow(false)
            every { downloadProgress } returns MutableStateFlow(0f)
            every { downloadError } returns MutableStateFlow("")
            coEvery { isModelDownloaded() } returns false
        },
        oauthManager = mockk(relaxed = true),
        oauthCallbackReceiver = com.hermes.agent.data.oauth.OAuthCallbackReceiver(),
        modelProbe = mockk(relaxed = true),
        heartbeatScheduler = mockk(relaxed = true),
        presenceBeaconScheduler = mockk(relaxed = true),
        presenceManager = mockk(relaxed = true),
        tailnet = mockk(relaxed = true),
    )
}
