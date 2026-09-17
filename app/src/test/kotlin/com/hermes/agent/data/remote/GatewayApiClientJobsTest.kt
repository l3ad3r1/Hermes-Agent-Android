package com.hermes.agent.data.remote

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pins the `/api/jobs` wire shape to a trimmed response captured from a live Hermes 0.21.3 gateway. */
class GatewayApiClientJobsTest {

    private val requests = mutableListOf<Request>()

    private fun client(body: String): GatewayApiClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings(
            remoteGatewayUrl = "http://pc.tailnet.ts.net:8642/",
            remoteGatewayApiKey = "secret-key",
        )
        val dispatchers = object : DispatcherProvider {
            override val io = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val main = Dispatchers.Unconfined
            override val unconfined = Dispatchers.Unconfined
        }
        return GatewayApiClient(http, Json { ignoreUnknownKeys = true }, settings, dispatchers)
    }

    @Test
    fun `lists jobs from a named profile including paused ones`() = runTest {
        val jobs = client(
            """{"jobs":[{"id":"1f05fe8a4946","name":"Reddit bot drafting rounds",
               "schedule":{"kind":"cron","expr":"0 9,12,15,18 * * *"},"schedule_display":"0 9,12,15,18 * * *",
               "enabled":false,"state":"paused","next_run_at":null,
               "last_run_at":"2026-09-17T18:00:28+05:30","last_status":"ok","last_error":null}]}""",
        ).listJobs("redditbot")

        val request = requests.single()
        assertEquals("http://pc.tailnet.ts.net:8642/p/redditbot/api/jobs?include_disabled=true", request.url.toString())
        assertEquals("Bearer secret-key", request.header("Authorization"))
        val job = jobs.single()
        assertEquals("1f05fe8a4946", job.id)
        assertEquals("0 9,12,15,18 * * *", job.schedule)
        assertFalse(job.enabled)
        assertEquals(null, job.nextRunAt)
        assertEquals("ok", job.lastStatus)
    }

    @Test
    fun `job action posts to the default profile and unwraps the job`() = runTest {
        val job = client("""{"job":{"id":"e6c2c53080d7","name":"Job applier","enabled":false,"state":"paused"}}""")
            .jobAction("e6c2c53080d7", "pause")

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("http://pc.tailnet.ts.net:8642/api/jobs/e6c2c53080d7/pause", request.url.toString())
        assertEquals("paused", job.state)
    }
}
