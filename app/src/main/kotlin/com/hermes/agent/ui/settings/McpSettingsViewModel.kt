package com.hermes.agent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.data.mcp.McpManager
import com.hermes.agent.domain.mcp.McpRepository
import com.hermes.agent.domain.mcp.McpServerConfig
import com.hermes.agent.domain.mcp.McpTransportType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Settings-side state for the MCP server registry.
 *
 * Until this existed there was no code path anywhere — UI or tool — that called
 * [McpRepository.saveServer], so `mcp_servers` was always empty, no MCP tool was
 * ever registered, and progressive disclosure never had anything to defer. The
 * client, the manager, the Room tables and the tool-search bridge were all
 * present and unit-tested but unreachable in a shipped build.
 */
@HiltViewModel
class McpSettingsViewModel @Inject constructor(
    private val mcpRepository: McpRepository,
    private val mcpManager: McpManager,
) : ViewModel() {

    // No withContext(Dispatchers.IO) here on purpose: McpClient already runs every
    // network call on IO, so wrapping again only moved work off the ViewModel's
    // dispatcher and made completion unobservable to callers (and to tests).

    /** Configured servers, live from Room. */
    val servers: StateFlow<List<McpServerConfig>> = mcpRepository.getServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _toolCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Number of cached tools per server id, for the "N tools" line on each card. */
    val toolCounts: StateFlow<Map<String, Int>> = _toolCounts.asStateFlow()

    private val _busyServerId = MutableStateFlow<String?>(null)

    /** Id of the server currently syncing, so its row can show progress. */
    val busyServerId: StateFlow<String?> = _busyServerId.asStateFlow()

    init {
        refreshToolCounts()
    }

    fun refreshToolCounts() = viewModelScope.launch {
        runCatching { mcpRepository.getAllCachedTools() }
            .onSuccess { tools -> _toolCounts.value = tools.groupingBy { it.serverId }.eachCount() }
            .onFailure { Timber.tag("Mcp").w(it, "could not read cached tools") }
    }

    /**
     * Add a server and immediately try to sync it, so the user finds out whether
     * the URL works without a second action. A server that fails the handshake
     * is still saved (with its error recorded) rather than silently dropped —
     * the URL may be a typo the user wants to correct rather than retype.
     */
    fun addServer(
        name: String,
        url: String,
        transport: McpTransportType,
        headerName: String,
        headerValue: String,
        onResult: (Boolean, String) -> Unit,
    ) = viewModelScope.launch {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isEmpty()) {
            onResult(false, "Give the server a name.")
            return@launch
        }
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            onResult(false, "The URL must start with http:// or https://.")
            return@launch
        }
        val headers = if (headerName.isNotBlank()) {
            mapOf(headerName.trim() to headerValue.trim())
        } else {
            emptyMap()
        }
        val server = McpServerConfig(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            url = trimmedUrl,
            transport = transport,
            headers = headers,
        )
        val result = runCatching {
            mcpRepository.saveServer(server)
            mcpManager.syncServer(server.id)
        }
        refreshToolCounts()
        result.fold(
            onSuccess = { sync ->
                sync.fold(
                    onSuccess = { tools ->
                        onResult(true, "Connected. ${tools.size} tool(s) discovered.")
                    },
                    onFailure = { e ->
                        onResult(false, "Saved, but the server did not respond: ${e.message}")
                    },
                )
            },
            onFailure = { e -> onResult(false, "Could not save the server: ${e.message}") },
        )
    }

    fun setEnabled(serverId: String, enabled: Boolean) = viewModelScope.launch {
        runCatching {
            mcpRepository.setServerEnabled(serverId, enabled)
            if (enabled) mcpManager.syncServer(serverId) else mcpManager.unregisterServerTools(serverId)
        }.onFailure { Timber.tag("Mcp").w(it, "could not toggle server %s", serverId) }
        refreshToolCounts()
    }

    fun sync(serverId: String, onResult: (Boolean, String) -> Unit) = viewModelScope.launch {
        _busyServerId.value = serverId
        val result = runCatching { mcpManager.syncServer(serverId) }
        _busyServerId.value = null
        refreshToolCounts()
        result.fold(
            onSuccess = { sync ->
                sync.fold(
                    onSuccess = { tools -> onResult(true, "${tools.size} tool(s) available.") },
                    onFailure = { e -> onResult(false, e.message ?: "Sync failed.") },
                )
            },
            onFailure = { e -> onResult(false, e.message ?: "Sync failed.") },
        )
    }

    /**
     * Remove a server, its cached tools, and any tool it registered. Order
     * matters: unregister first, so a tool cannot outlive the server row and
     * keep answering calls from a server the user believes they deleted.
     */
    fun deleteServer(serverId: String) = viewModelScope.launch {
        runCatching {
            mcpManager.unregisterServerTools(serverId)
            mcpRepository.clearCachedTools(serverId)
            mcpRepository.deleteServer(serverId)
        }.onFailure { Timber.tag("Mcp").w(it, "could not delete server %s", serverId) }
        refreshToolCounts()
    }
}
