package com.hermes.agent.data.plugin

import com.hermes.agent.data.local.dao.ScriptPluginDao
import com.hermes.agent.data.local.entity.ScriptPluginEntity
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptPluginHost
import com.hermes.agent.data.plugin.script.ScriptPluginManifest
import com.hermes.agent.data.plugin.script.ScriptToolSpec
import com.hermes.agent.data.tool.ToolRegistryImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptPluginRepositoryTest {

    private class FakeScriptPluginDao : ScriptPluginDao {
        val items = mutableMapOf<String, ScriptPluginEntity>()
        val flow = MutableStateFlow<List<ScriptPluginEntity>>(emptyList())

        private fun emit() {
            flow.value = items.values.toList()
        }

        override fun observeAll(): Flow<List<ScriptPluginEntity>> = flow

        override suspend fun getAll(): List<ScriptPluginEntity> = items.values.toList()

        override suspend fun getEnabled(): List<ScriptPluginEntity> =
            items.values.filter { it.enabled }

        override suspend fun getById(id: String): ScriptPluginEntity? = items[id]

        override suspend fun upsert(entity: ScriptPluginEntity) {
            items[entity.id] = entity
            emit()
        }

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            items[id]?.let {
                items[id] = it.copy(enabled = enabled)
                emit()
            }
        }

        override suspend fun delete(id: String) {
            items.remove(id)
            emit()
        }
    }

    /** No-op host: these tests only exercise install/enable/reload bookkeeping. */
    private class FakeScriptPluginHost : ScriptPluginHost {
        override fun log(pluginId: String, message: String) = Unit
        override fun readData(pluginId: String, collection: String, query: String) = ""
        override fun writeData(pluginId: String, collection: String, payload: String) = ""
        override fun httpGet(pluginId: String, url: String) = ""
    }

    private fun sampleManifest(id: String = "test-plugin", toolName: String = "test_tool") = ScriptPluginManifest(
        id = id,
        name = "Test Plugin",
        version = "1.0.0",
        author = "Tester",
        description = "Test plugin description",
        type = "tool",
        main = "hermes.registerTool('$toolName', function(args) { return 'result'; });",
        permissions = listOf("network"),
        tools = listOf(
            ScriptToolSpec(
                name = toolName,
                description = "desc",
                parameters = emptyList(),
            )
        )
    )

    @Test
    fun `install persists entity with approved permissions and reloads tools`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = ToolRegistryImpl()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("my-plugin", "my_custom_tool")
        val result = repository.install(manifest, "https://example.com/manifest.json")

        assertTrue(result.isSuccess)
        val installed = dao.getById("my-plugin")
        assertNotNull(installed)
        assertEquals("my-plugin", installed?.id)
        assertEquals("network", installed?.grantedPermissions)
        assertTrue(installed?.enabled == true)

        val tool = registry.byName("my_custom_tool")
        assertNotNull(tool)
        assertEquals("my_custom_tool", tool?.descriptor?.name)
    }

    @Test
    fun `setEnabled false unregisters tools from registry`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = ToolRegistryImpl()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("toggle-plugin", "toggle_tool")
        repository.install(manifest, "https://example.com/manifest.json")
        assertNotNull(registry.byName("toggle_tool"))

        repository.setEnabled("toggle-plugin", false)
        assertNull(registry.byName("toggle_tool"))

        repository.setEnabled("toggle-plugin", true)
        assertNotNull(registry.byName("toggle_tool"))
    }

    @Test
    fun `uninstall deletes from dao and unregisters tool`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = ToolRegistryImpl()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("del-plugin", "del_tool")
        repository.install(manifest, "https://example.com/manifest.json")
        assertNotNull(registry.byName("del_tool"))

        repository.uninstall("del-plugin")
        assertNull(dao.getById("del-plugin"))
        assertNull(registry.byName("del_tool"))
    }

    @Test
    fun `observeInstalled reflects changes`() = runTest {
        val dao = FakeScriptPluginDao()
        val engine = ScriptPluginEngine()
        val registry = ToolRegistryImpl()
        val repository = ScriptPluginRepository(dao, engine, registry, FakeScriptPluginHost())

        val manifest = sampleManifest("obs-plugin", "obs_tool")
        repository.install(manifest, "https://example.com/manifest.json")

        val list = repository.observeInstalled().first()
        assertEquals(1, list.size)
        assertEquals("obs-plugin", list[0].id)
    }
}
