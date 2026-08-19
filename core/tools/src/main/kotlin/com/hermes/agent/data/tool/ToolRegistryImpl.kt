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
class ToolRegistryImpl @Inject constructor(
    tools: Set<@JvmSuppressWildcards Tool>,
) : ToolRegistry {

    constructor() : this(emptySet())


    private val toolMap = ConcurrentHashMap<String, Tool>()

    init {
        for (tool in tools) {
            register(tool)
        }
    }

    override fun all(): List<Tool> =
        toolMap.values.sortedWith(
            compareBy({ it.descriptor.category }, { it.descriptor.name })
        )

    override fun byName(name: String): Tool? = toolMap[name]

    override fun register(tool: Tool) {
        toolMap[tool.descriptor.name] = tool
    }

    override fun unregister(name: String) {
        toolMap.remove(name)
    }
}

