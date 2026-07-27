package com.hermes.agent.ui.home

import com.hermes.agent.data.llm.LocalLlmManager
import com.hermes.agent.data.llm.ModelCatalog
import com.hermes.agent.data.memory.UserModelService
import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.domain.repository.ConversationRepository
import com.hermes.agent.domain.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The home screen's "Active model" card must name the model a turn would
 * actually run on.
 *
 * It previously read `settings.cloudModel` unconditionally, so a device with
 * cloud switched off — running purely on-device — was still told a cloud model
 * was active. These pin the card to the same two conditions
 * [com.hermes.agent.data.llm.HybridLlmRouter] routes on.
 *
 * Runs under Robolectric because the custom-model label parses a SAF
 * `content://` URI, and `android.net.Uri` is a stub outside it.
 */
@RunWith(RobolectricTestRunner::class)
class HomeActiveModelTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        settings: UserSettings,
        localDownloaded: Boolean,
    ): HomeViewModel {
        val settingsRepository = mockk<SettingsRepository>(relaxed = true) {
            every { observe() } returns flowOf(settings)
        }
        val localLlmManager = mockk<LocalLlmManager>(relaxed = true) {
            coEvery { isModelDownloaded() } returns localDownloaded
        }
        val conversations = mockk<ConversationRepository>(relaxed = true) {
            every { observeConversations() } returns flowOf(emptyList())
        }
        val memories = mockk<MemoryRepository>(relaxed = true) {
            every { observeMemories() } returns flowOf(emptyList())
        }
        return HomeViewModel(conversations, settingsRepository, localLlmManager, memories)
    }

    @Test
    fun `names the local model when cloud is switched off`() = runTest {
        val vm = viewModel(
            settings = UserSettings(
                cloudEnabled = false,
                cloudApiKey = "sk-something",
                cloudModel = "gpt-4o-mini",
                selectedModelId = "",
            ),
            localDownloaded = true,
        )
        assertEquals(ModelCatalog.DEFAULT.displayName, vm.modelName.first { it.isNotEmpty() })
    }

    @Test
    fun `names the local model when cloud is on but has no key`() = runTest {
        // The router treats a keyless cloud as unavailable and falls through to
        // local, so the card has to agree — the toggle alone is not enough.
        val vm = viewModel(
            settings = UserSettings(
                cloudEnabled = true,
                cloudApiKey = "",
                cloudModel = "gpt-4o-mini",
            ),
            localDownloaded = true,
        )
        assertEquals(ModelCatalog.DEFAULT.displayName, vm.modelName.first { it.isNotEmpty() })
    }

    @Test
    fun `names the cloud model only when cloud is on and keyed`() = runTest {
        val vm = viewModel(
            settings = UserSettings(
                cloudEnabled = true,
                cloudApiKey = "sk-something",
                cloudModel = "gpt-4o-mini",
            ),
            localDownloaded = true,
        )
        assertEquals("gpt-4o-mini", vm.modelName.first { it.isNotEmpty() })
    }

    @Test
    fun `prefers a user-picked gguf over the catalog name`() = runTest {
        val vm = viewModel(
            settings = UserSettings(
                cloudEnabled = false,
                localModelUri = "content://com.android.providers.downloads/document/qwen2.5-3b.gguf",
            ),
            localDownloaded = true,
        )
        assertEquals("qwen2.5-3b.gguf", vm.modelName.first { it.isNotEmpty() })
    }

    @Test
    fun `reports nothing configured when neither provider is usable`() = runTest {
        val vm = viewModel(
            settings = UserSettings(cloudEnabled = false),
            localDownloaded = false,
        )
        assertEquals("not configured", vm.modelName.first { it.isNotEmpty() })
    }
}
