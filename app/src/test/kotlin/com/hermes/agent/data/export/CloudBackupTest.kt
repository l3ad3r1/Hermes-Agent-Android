package com.hermes.agent.data.export

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CloudBackupTest {

    @Test
    fun `a device name becomes a safe folder name`() {
        assertEquals("galaxy-tab-s9", CloudBackupPolicy.deviceSlug("Galaxy Tab S9"))
        assertEquals("s24-ultra", CloudBackupPolicy.deviceSlug("  S24 Ultra!! "))
        assertEquals("device", CloudBackupPolicy.deviceSlug("../.."))
    }

    @Test
    fun `backups land in a folder per device and sort by date`() {
        val path = CloudBackupPolicy.path("Tab S9", 1_790_000_000_000L)
        assertEquals("backups/hermes/tab-s9/hermes-2026-09-21-1413.hbk", path)
        assertTrue(CloudBackupPolicy.path("Tab S9", 1_790_100_000_000L) > path)
    }

    @Test
    fun `only the newest backups are kept and other files are never touched`() {
        val names = listOf("hermes-2026-09-01-0800.hbk", "hermes-2026-09-03-0800.hbk", "hermes-2026-09-02-0800.hbk", "README.md")
        assertEquals(listOf("hermes-2026-09-01-0800.hbk"), CloudBackupPolicy.toDelete(names, keep = 2))
        assertEquals(emptyList<String>(), CloudBackupPolicy.toDelete(names, keep = 10))
    }

    @Test
    fun `repo must look like owner slash name`() {
        assertTrue(CloudBackupPolicy.isRepo("yourname/app-backups"))
        assertTrue(!CloudBackupPolicy.isRepo("app-backups"))
        assertTrue(!CloudBackupPolicy.isRepo("https://github.com/a/b"))
    }

    private fun api(vararg routes: Pair<String, Pair<Int, String>>, seen: MutableList<String> = mutableListOf()): GitHubBackupApi {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            seen += "${request.method} ${request.url.encodedPath}"
            val hit = routes.firstOrNull { request.url.encodedPath.endsWith(it.first) }?.second ?: (404 to """{"message":"Not Found"}""")
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(hit.first).message("x")
                .body(hit.second.toResponseBody("application/json".toMediaType())).build()
        }).build()
        return GitHubBackupApi(client, "https://api.test")
    }

    @Test
    fun `listing gathers every device's backups, newest first`() {
        val api = api(
            "/repos/o/r" to (200 to "{}"),
            "/contents/backups/hermes" to (200 to """[{"type":"dir","name":"tab-s9","path":"backups/hermes/tab-s9"},{"type":"dir","name":"s24-ultra","path":"backups/hermes/s24-ultra"}]"""),
            "/contents/backups/hermes/tab-s9" to (200 to """[{"type":"file","name":"hermes-2026-09-01-0800.hbk","path":"backups/hermes/tab-s9/hermes-2026-09-01-0800.hbk","sha":"a","size":10}]"""),
            "/contents/backups/hermes/s24-ultra" to (200 to """[{"type":"file","name":"hermes-2026-09-02-0800.hbk","path":"backups/hermes/s24-ultra/hermes-2026-09-02-0800.hbk","sha":"b","size":20},{"type":"file","name":"notes.txt","path":"x","sha":"c","size":1}]"""),
        )

        val files = api.list("o/r", "tok")

        assertEquals(listOf("s24-ultra", "tab-s9"), files.map { it.device })
        assertEquals(20L, files.first().size)
    }

    @Test
    fun `a repo with no backups yet lists as empty`() {
        assertEquals(emptyList<CloudFile>(), api("/repos/o/r" to (200 to "{}")).list("o/r", "tok"))
    }

    @Test
    fun `a brand-new empty repo lists as empty`() {
        val empty = api("/repos/o/r" to (200 to "{}"), "/contents/backups/hermes" to (409 to """{"message":"Git Repository is empty."}"""))
        assertEquals(emptyList<CloudFile>(), empty.list("o/r", "tok"))
    }

    @Test
    fun `a missing repo or a bad token is an error, not an empty list`() {
        val missing = runCatching { api().list("o/none", "tok") }.exceptionOrNull()
        assertTrue(missing!!.message.orEmpty(), missing.message.orEmpty().contains("cannot find that repo"))
        val rejected = runCatching { api("/repos/o/r" to (401 to """{"message":"Bad credentials"}""")).list("o/r", "bad") }.exceptionOrNull()
        assertTrue(rejected!!.message.orEmpty(), rejected.message.orEmpty().contains("rejected the token"))
    }

    @Test
    fun `an oversized backup is refused before anything is sent`() {
        val seen = mutableListOf<String>()
        try {
            api(seen = seen).put("o/r", "tok", "backups/hermes/x/y.hbk", ByteArray((CloudBackupPolicy.MAX_BYTES + 1).toInt()), "m")
            fail("expected the size check to refuse")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("too big"))
        }
        assertEquals(emptyList<String>(), seen)
    }
}
