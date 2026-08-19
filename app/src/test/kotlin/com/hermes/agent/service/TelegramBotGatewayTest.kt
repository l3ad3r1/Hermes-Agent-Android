package com.hermes.agent.service

import com.hermes.agent.data.settings.SettingsRepository
import com.hermes.agent.data.settings.UserSettings
import com.hermes.agent.domain.agent.Orchestrator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Test

class TelegramBotGatewayTest {

    private val settingsRepository = mockk<SettingsRepository>()
    private val orchestrator = mockk<Orchestrator>()
    private val okHttpClient = OkHttpClient()

    @Test
    fun testTelegramBotGatewayInstantiation() = runTest {
        coEvery { settingsRepository.observe() } returns flowOf(
            UserSettings(
                telegramBotEnabled = false,
                telegramBotToken = "",
            ),
        )

        val gateway = TelegramBotGateway(
            settingsRepository = settingsRepository,
            orchestrator = orchestrator,
            okHttpClient = okHttpClient,
        )

        assertNotNull(gateway)
        gateway.start(TestScope())
        gateway.stop()
    }
}
