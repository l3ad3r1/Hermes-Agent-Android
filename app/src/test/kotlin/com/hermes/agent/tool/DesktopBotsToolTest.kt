package com.hermes.agent.tool

import com.hermes.agent.data.remote.GatewayApiClient
import com.hermes.agent.data.remote.RemoteJob
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopBotsToolTest {

    private val gateway = mockk<GatewayApiClient>()
    private val settings = mockk<SettingsRepository>()
    private val tool = DesktopBotsTool(gateway, settings)

    private val job = RemoteJob(
        id = "e6c2c53080d7", name = "Job applier twice-daily", schedule = "0 9,18 * * *",
        enabled = true, state = "scheduled", nextRunAt = "2026-09-18T09:00:00+05:30",
        lastRunAt = null, lastStatus = null, lastError = null,
    )

    private fun configured() {
        coEvery { settings.current() } returns UserSettings(
            remoteGatewayUrl = "http://pc.tailnet.ts.net:8642",
            remoteGatewayApiKey = "k".repeat(32),
        )
    }

    private fun args(vararg pairs: Pair<String, String>) = pairs.associate { it.first to JsonPrimitive(it.second) }

    @Test
    fun `list reports each bot with its id`() = runTest {
        configured()
        coEvery { gateway.listJobs(null) } returns listOf(job, job.copy(id = "43d822b140a3", name = "Watchdog", enabled = false))

        val result = tool.execute(args("action" to "list"))

        assertTrue(result.success)
        assertTrue(result.output.contains("Job applier twice-daily [id e6c2c53080d7] scheduled"))
        assertTrue(result.output.contains("Watchdog [id 43d822b140a3] paused"))
    }

    @Test
    fun `pause forwards the job id`() = runTest {
        configured()
        coEvery { gateway.jobAction("e6c2c53080d7", "pause", null) } returns job.copy(enabled = false, state = "paused")

        val result = tool.execute(args("action" to "pause", "job_id" to "e6c2c53080d7"))

        assertTrue(result.success)
        coVerify { gateway.jobAction("e6c2c53080d7", "pause", null) }
    }

    @Test
    fun `rejects a job id that could escape the path`() = runTest {
        configured()

        val result = tool.execute(args("action" to "run", "job_id" to "../sessions"))

        assertFalse(result.success)
        coVerify(exactly = 0) { gateway.jobAction(any(), any(), any()) }
    }

    @Test
    fun `named profile is forwarded and validated`() = runTest {
        configured()
        coEvery { gateway.listJobs("redditbot") } returns listOf(job)

        assertTrue(tool.execute(args("action" to "list", "profile" to "redditbot")).success)
        assertFalse(tool.execute(args("action" to "list", "profile" to "a/b")).success)
        coVerify(exactly = 1) { gateway.listJobs(any()) }
    }

    @Test
    fun `explains missing configuration`() = runTest {
        coEvery { settings.current() } returns UserSettings()

        val result = tool.execute(args("action" to "list"))

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("Remote gateway"))
    }
}
