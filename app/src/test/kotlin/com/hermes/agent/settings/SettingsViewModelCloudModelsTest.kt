package com.hermes.agent.ui.settings

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.hermes.agent.data.backup.GithubBackupService
import com.hermes.agent.data.export.SessionExporter
import com.hermes.agent.data.llm.CloudModelCatalog
import com.hermes.agent.data.llm.LocalLlmManager
import com.hermes.agent.data.security.KeystoreManager
import com.hermes.agent.data.security.KnoxSecurityManager
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.data.update.OtaInstaller
import com.hermes.agent.data.update.OtaUpdateChecker
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelCloudModelsTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `automatic discovery reuses primary list for inherited specialist endpoint`() = runTest(dispatcher) {
        val configured = UserSettings(
            cloudEnabled = true,
            cloudBaseUrl = "https://models.example/v1",
            cloudApiKey = "key",
            auxBaseUrl = "",
            auxApiKey = "",
        )
        val settingsFlow = MutableStateFlow(configured)
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { observe() } returns settingsFlow
            coEvery { current() } answers { settingsFlow.value }
        }
        val catalog = mockk<CloudModelCatalog>()
        coEvery { catalog.listModels(any(), any()) } returns listOf("model-a", "model-b")
        val localManager = mockk<LocalLlmManager>(relaxed = true) {
            every { isDownloading } returns MutableStateFlow(false)
            every { downloadProgress } returns MutableStateFlow(0f)
            every { downloadError } returns MutableStateFlow("")
            coEvery { isModelDownloaded() } returns false
        }

        val viewModel = SettingsViewModel(
            appContext = mockk<Context>(relaxed = true),
            settingsRepository = settingsRepository,
            knox = mockk<KnoxSecurityManager>(relaxed = true),
            keystore = mockk<KeystoreManager>(relaxed = true),
            otaUpdateChecker = mockk<OtaUpdateChecker>(relaxed = true),
            otaInstaller = mockk<OtaInstaller>(relaxed = true),
            githubBackupService = mockk<GithubBackupService>(relaxed = true),
            sessionExporter = mockk<SessionExporter>(relaxed = true),
            cloudModelCatalog = catalog,
            localLlmManager = localManager,
        )
        advanceUntilIdle()

        val primary = viewModel.primaryModelDiscovery.value
        val specialist = viewModel.specialistModelDiscovery.value
        assertTrue(primary is ModelDiscoveryUiState.Ready)
        assertTrue(specialist is ModelDiscoveryUiState.Ready)
        assertEquals(listOf("model-a", "model-b"), (primary as ModelDiscoveryUiState.Ready).models)
        assertEquals(primary, specialist)
        coVerify(exactly = 1) {
            catalog.listModels("https://models.example/v1", "key")
        }
    }
}