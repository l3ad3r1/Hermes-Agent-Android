package com.hermes.agent.data.remote

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.util.DispatcherProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The Reddit approvals wire shape, including the digest that stops a changed draft from being posted. */
class GatewayApiClientRedditTest {

    private val requests = mutableListOf<Request>()
    private val sentBodies = mutableListOf<String>()

    private fun client(body: String, status: Int = 200): GatewayApiClient {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            sentBodies += request.body?.let { Buffer().also { buf -> it.writeTo(buf) }.readUtf8() }.orEmpty()
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
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
        val tailnet = mockk<TailnetNode>()
        every { tailnet.wrap(any()) } answers { firstArg() }
        return GatewayApiClient(http, Json { ignoreUnknownKeys = true }, settings, dispatchers, tailnet)
    }

    @Test
    fun `drafts carry the digest of the original and of the rewrite`() = runTest {
        val drafts = client(
            """{"drafts":[{"code":"977V","sub":"microsaas","title":"t","url":"u","text":"original",
               "critic":"NotebookLM","score":3,"issues":"robotic","rewrite":"rewritten",
               "hash":"aaaa1111bbbb2222","rewrite_hash":"cccc3333dddd4444"},
               {"code":"OLD1","sub":"x","title":"t","url":"u","text":"no digests from an older gateway"}]}""",
        ).listRedditDrafts()

        val d = drafts.first()
        assertEquals("aaaa1111bbbb2222", d.hash)
        assertEquals("cccc3333dddd4444", d.rewriteHash)
        assertEquals("aaaa1111bbbb2222", d.hashFor(useRewrite = false))
        assertEquals("cccc3333dddd4444", d.hashFor(useRewrite = true))
        val old = drafts.last()
        assertNull(old.hash)
        assertNull(old.hashFor(useRewrite = false))
    }

    @Test
    fun `approving sends the digest of the version being approved`() = runTest {
        val result = client("""{"result":"Posted 977V-R: https://www.reddit.com/x"}""")
            .approveRedditDraft("977V", useRewrite = true, expectedHash = "cccc3333dddd4444")

        assertEquals("Posted 977V-R: https://www.reddit.com/x", result)
        assertEquals("http://pc.tailnet.ts.net:8642/api/reddit/drafts/977V/approve", requests.single().url.toString())
        val sent = Json.parseToJsonElement(sentBodies.single()).jsonObject
        assertTrue(sent["rewrite"]!!.let { (it as JsonPrimitive).boolean })
        assertEquals("cccc3333dddd4444", (sent["hash"] as JsonPrimitive).content)
    }

    @Test
    fun `approving without a digest sends none, as before`() = runTest {
        client("""{"result":"Posted 977V"}""").approveRedditDraft("977V", useRewrite = false)

        val sent = Json.parseToJsonElement(sentBodies.single()).jsonObject
        assertFalse(sent["rewrite"]!!.let { (it as JsonPrimitive).boolean })
        assertFalse(sent.containsKey("hash"))
    }

    @Test
    fun `a refused post reports the gateway's reason, not raw json`() = runTest {
        val gateway = client(
            """{"error":"Not posted: the approved draft or destination changed. Review a fresh card.","result":"x"}""",
            status = 409,
        )
        try {
            gateway.approveRedditDraft("977V", useRewrite = false, expectedHash = "aaaa1111bbbb2222")
            fail("expected a refusal")
        } catch (e: IOException) {
            assertEquals(
                "approve 977V failed: 409 Not posted: the approved draft or destination changed. Review a fresh card.",
                e.message,
            )
        }
    }

    @Test
    fun `a refusal that is not json still shows what the gateway said`() = runTest {
        try {
            client("Bad gateway", status = 502).skipRedditDraft("977V")
            fail("expected a failure")
        } catch (e: IOException) {
            assertEquals("skip 977V failed: 502 Bad gateway", e.message)
        }
    }
}
