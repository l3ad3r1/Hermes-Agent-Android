package com.hermes.agent.ui.settings

import com.hermes.agent.data.mcp.McpManager
import com.hermes.agent.domain.mcp.McpRepository
import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpToolDefinition
import com.hermes.agent.domain.mcp.McpTransportType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [McpSettingsViewModel] — the only code path that can put a row
 * in `mcp_servers`. Before it existed the table was always empty, so every MCP
 * tool was unreachable no matter how well the client underneath worked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** In-memory stand-in for the Room-backed repository. */
    private class FakeMcpRepository : McpRepository {
        val saved = MutableStateFlow<List<McpServerConfig>>(emptyList())
        val cachedTools = mutableMapOf<String, List<McpToolDefinition>>()
        val clearedToolsFor = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override fun getServers(): Flow<List<McpServerConfig>> = saved
        override suspend fun getAllServers(): List<McpServerConfig> = saved.value
        override suspend fun getServer(id: String): McpServerConfig? = saved.value.find { it.id == id }
        override suspend fun saveServer(server: McpServerConfig) {
            saved.value = saved.value.filterNot { it.id == server.id } + server
        }

        override suspend fun deleteServer(id: String) {
            deleted += id
            saved.value = saved.value.filterNot { it.id == id }
        }

        override suspend fun updateServerError(id: String, lastError: String?) {
            saved.value = saved.value.map { if (it.id == id) it.copy(lastError = lastError) else it }
        }

        override suspend fun setServerEnabled(id: String, enabled: Boolean) {
            saved.value = saved.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }

        override fun getCachedTools(serverId: String): Flow<List<McpToolDefinition>> =
            saved.map { cachedTools[serverId].orEmpty() }

        override suspend fun getAllCachedTools(): List<McpToolDefinition> =
            cachedTools.values.flatten()

        override suspend fun saveCachedTools(serverId: String, tools: List<McpToolDefinition>) {
            cachedTools[serverId] = tools
        }

        override suspend fun clearCachedTools(serverId: String) {
            clearedToolsFor += serverId
            cachedTools.remove(serverId)
        }
    }

    private lateinit var repo: FakeMcpRepository
    private lateinit var manager: McpManager

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = FakeMcpRepository()
        manager = mockk(relaxed = true)
        coEvery { manager.syncServer(any()) } returns Result.success(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = McpSettingsViewModel(repo, manager)

    @Test
    fun `a blank name is rejected and nothing is saved`() = runTest(dispatcher) {
        var ok = true
        var message = ""
        vm().addServer("   ", "https://example.com/mcp", McpTransportType.HTTP, "", "") { o, m ->
            ok = o; message = m
        }
        advanceUntilIdle()

        assertFalse(ok)
        assertTrue(message.contains("name"))
        assertTrue(repo.saved.value.isEmpty())
    }

    @Test
    fun `a URL without a scheme is rejected and nothing is saved`() = runTest(dispatcher) {
        var ok = true
        vm().addServer("ctx", "example.com/mcp", McpTransportType.HTTP, "", "") { o, _ -> ok = o }
        advanceUntilIdle()

        assertFalse(ok)
        assertTrue(repo.saved.value.isEmpty())
        coVerify(exactly = 0) { manager.syncServer(any()) }
    }

    @Test
    fun `a valid server is saved, carries its auth header, and is synced`() = runTest(dispatcher) {
        coEvery { manager.syncServer(any()) } returns Result.success(
            listOf(
                McpToolDefinition("s", "search", "mcp__ctx__search", "d", "{}"),
                McpToolDefinition("s", "fetch", "mcp__ctx__fetch", "d", "{}"),
            )
        )
        var ok = false
        var message = ""
        vm().addServer(" ctx ", " https://example.com/mcp ", McpTransportType.SSE, "Authorization", "Bearer t") { o, m ->
            ok = o; message = m
        }
        advanceUntilIdle()

        assertTrue(message, ok)
        val saved = repo.saved.value.single()
        assertEquals("ctx", saved.name)
        assertEquals("https://example.com/mcp", saved.url)
        assertEquals(McpTransportType.SSE, saved.transport)
        assertEquals(mapOf("Authorization" to "Bearer t"), saved.headers)
        assertTrue(message.contains("2 tool"))
        coVerify(exactly = 1) { manager.syncServer(saved.id) }
    }

    @Test
    fun `a server that fails its handshake is still saved so the URL can be corrected`() =
        runTest(dispatcher) {
            coEvery { manager.syncServer(any()) } returns Result.failure(Exception("connection refused"))
            var ok = true
            var message = ""
            vm().addServer("ctx", "https://nope.invalid/mcp", McpTransportType.HTTP, "", "") { o, m ->
                ok = o; message = m
            }
            advanceUntilIdle()

            assertFalse(ok)
            assertTrue(message, message.contains("connection refused"))
            assertEquals(1, repo.saved.value.size)
        }

    @Test
    fun `no auth header means no headers map entry`() = runTest(dispatcher) {
        vm().addServer("ctx", "https://example.com/mcp", McpTransportType.HTTP, "", "ignored") { _, _ -> }
        advanceUntilIdle()

        assertTrue(repo.saved.value.single().headers.isEmpty())
    }

    @Test
    fun `deleting unregisters the tools before removing the row`() = runTest(dispatcher) {
        val server = McpServerConfig(id = "s1", name = "ctx", url = "https://example.com/mcp")
        repo.saveServer(server)
        repo.saveCachedTools("s1", listOf(McpToolDefinition("s1", "t", "mcp__ctx__t", "d", "{}")))

        vm().deleteServer("s1")
        advanceUntilIdle()

        // Unregister must happen first: a tool that outlives its server row would
        // keep answering calls for a server the user believes they deleted.
        coVerify(exactly = 1) { manager.unregisterServerTools("s1") }
        assertEquals(listOf("s1"), repo.clearedToolsFor)
        assertEquals(listOf("s1"), repo.deleted)
        assertTrue(repo.saved.value.isEmpty())
    }

    @Test
    fun `disabling a server unregisters its tools instead of syncing`() = runTest(dispatcher) {
        repo.saveServer(McpServerConfig(id = "s1", name = "ctx", url = "https://example.com/mcp"))

        vm().setEnabled("s1", false)
        advanceUntilIdle()

        assertFalse(repo.saved.value.single().enabled)
        coVerify(exactly = 1) { manager.unregisterServerTools("s1") }
        coVerify(exactly = 0) { manager.syncServer("s1") }
    }

    @Test
    fun `tool counts are grouped per server`() = runTest(dispatcher) {
        repo.saveCachedTools("a", listOf(McpToolDefinition("a", "t1", "q1", "d", "{}")))
        repo.saveCachedTools(
            "b",
            listOf(
                McpToolDefinition("b", "t2", "q2", "d", "{}"),
                McpToolDefinition("b", "t3", "q3", "d", "{}"),
            )
        )

        val vm = vm()
        advanceUntilIdle()

        assertEquals(mapOf("a" to 1, "b" to 2), vm.toolCounts.value)
    }
}
