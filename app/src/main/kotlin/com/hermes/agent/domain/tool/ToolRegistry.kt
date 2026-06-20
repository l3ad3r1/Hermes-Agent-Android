package com.hermes.agent.domain.tool

/**
 * Read/write access to the catalog of tools available to the orchestrator.
 *
 * Phase 2 ships a fixed set of first-party tools registered at app
 * startup. Phase 3 will allow dynamic registration of third-party plugins
 * through the gRPC sandbox.
 */
interface ToolRegistry {

    /** All registered tools, ordered by category then name. */
    fun all(): List<Tool>

    /** All registered tool descriptors, suitable for sending to an LLM. */
    fun descriptors(): List<ToolDescriptor> = all().map { it.descriptor }

    /** Look up a tool by its descriptor name, or null if not registered. */
    fun byName(name: String): Tool?

    /** Register a tool. Idempotent — re-registering replaces. */
    fun register(tool: Tool)

    /** Deregister a tool by name. */
    fun unregister(name: String)
}
