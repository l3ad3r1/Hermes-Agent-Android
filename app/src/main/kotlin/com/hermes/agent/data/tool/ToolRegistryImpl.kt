package com.hermes.agent.data.tool

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory [ToolRegistry] implementation.
 *
 * Tools are registered once at app startup by [com.hermes.agent.di.ToolsModule]
 * and remain resident for the lifetime of the process. Lookups are O(1).
 *
 * The implementation is thread-safe via a [ConcurrentHashMap]; the
 * orchestrator may invoke [byName] from any coroutine.
 */
@Singleton
class ToolRegistryImpl @Inject constructor() : ToolRegistry {

    private val tools = ConcurrentHashMap<String, Tool>()

    override fun all(): List<Tool> =
        tools.values.sortedWith(
            compareBy({ it.descriptor.category }, { it.descriptor.name })
        )

    override fun byName(name: String): Tool? = tools[name]

    override fun register(tool: Tool) {
        tools[tool.descriptor.name] = tool
    }

    override fun unregister(name: String) {
        tools.remove(name)
    }
}
