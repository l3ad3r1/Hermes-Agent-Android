package com.hermes.agent.data.llm

import android.content.Context
import com.arm.aichat.InferenceEngine
import com.hermes.agent.data.settings.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalLlmManagerLifecycleTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val engine = mockk<InferenceEngine>(relaxed = true)
    private val downloadCoordinator = mockk<LocalModelDownloadCoordinator>().also {
        every { it.isDownloading } returns MutableStateFlow(false)
        every { it.progress } returns MutableStateFlow(0f)
        every { it.error } returns MutableStateFlow("")
    }
    private val manager = LocalLlmManager(
        context = mockk<Context>(relaxed = true),
        settingsRepository = settingsRepository,
        downloadCoordinator = downloadCoordinator,
        engine = engine,
    )

    @Test
    fun `clearing a custom model unloads before persisting the empty uri`() = runTest {
        manager.setLocalModelUri("")

        coVerifyOrder {
            engine.cleanUp()
            settingsRepository.setLocalModelUri("")
        }
    }

    @Test
    fun `selecting another model unloads before persisting the model id`() = runTest {
        manager.setSelectedModelId("model-b")

        coVerifyOrder {
            engine.cleanUp()
            settingsRepository.setSelectedModelId("model-b")
        }
    }

    @Test
    fun `changing the download directory unloads before persisting the path`() = runTest {
        manager.setModelDownloadDir("/models")

        coVerifyOrder {
            engine.cleanUp()
            settingsRepository.setModelDownloadDir("/models")
        }
    }

    @Test
    fun `unload failure keeps the selection and tells the user how to retry`() = runTest {
        coEvery { engine.cleanUp() } throws IllegalStateException("native engine busy")
        every { downloadCoordinator.reportError(any()) } returns Unit

        manager.setLocalModelUri("")

        coVerify(exactly = 0) { settingsRepository.setLocalModelUri(any()) }
        verify {
            downloadCoordinator.reportError(match { message ->
                message.contains("try again", ignoreCase = true) &&
                    message.contains("native engine busy")
            })
        }
    }
}
