package com.ashotn.opencode.companion.api.mcp

import com.ashotn.opencode.companion.api.config.ConfigApiClient
import com.ashotn.opencode.companion.api.config.ConfigApiClient.McpServerConfig
import com.ashotn.opencode.companion.api.transport.ApiResult
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Unified view of a configured MCP server, combining:
 * - [config]: what the user has in their opencode config (`/config`)
 * - [connectionStatus]: the live runtime status from the OpenCode server (`/mcp`)
 * - [connectionError]: error message when [connectionStatus] is FAILED or NEEDS_CLIENT_REGISTRATION
 * - [isLoading]: true while a connect/disconnect call is in-flight for this server
 *
 * A server present in config but absent from the runtime status map will have
 * [connectionStatus] = null (server not yet seen by the running OpenCode instance).
 */
data class McpEntry(
    val name: String,
    val config: McpServerConfig,
    val connectionStatus: McpApiClient.McpConnectionStatus?,
    val connectionError: String?,
    val isLoading: Boolean = false,
) {
    /** True only when the server is enabled in config AND the runtime status is CONNECTED. */
    val isConnected: Boolean
        get() = config.enabled && connectionStatus == McpApiClient.McpConnectionStatus.CONNECTED

    /** True when enabled in config but the runtime reports a problem (failed, needs auth, etc.). */
    val hasIssue: Boolean
        get() = config.enabled && connectionStatus != null
                && connectionStatus != McpApiClient.McpConnectionStatus.CONNECTED
                && connectionStatus != McpApiClient.McpConnectionStatus.DISABLED
}

class McpService(
    private val configClient: ConfigApiClient = ConfigApiClient(),
    private val mcpClient: McpApiClient = McpApiClient(),
) {
    /** Names of servers currently mid-toggle (connect or disconnect call in-flight). */
    private val loadingNames = CopyOnWriteArraySet<String>()

    /**
     * Fetches both the config and runtime MCP status, then merges them by server name.
     *
     * - Servers only in config (not yet known to the running server) are included with a null status.
     * - Servers only in the runtime status (not in config) are excluded — they shouldn't exist.
     * - [McpEntry.isLoading] is true for any server currently being toggled via [connect]/[disconnect].
     *
     * Returns [ApiResult.Failure] if either HTTP call fails.
     */
    fun listMcpEntries(port: Int): ApiResult<List<McpEntry>> {
        val configResult = configClient.getMcpServers(port)
        if (configResult is ApiResult.Failure) return configResult

        val statusResult = mcpClient.listServers(port)
        if (statusResult is ApiResult.Failure) return statusResult

        val configServers = (configResult as ApiResult.Success).value
        val statusByName = (statusResult as ApiResult.Success).value.associateBy { it.name }

        val entries = configServers.map { serverConfig ->
            val runtimeStatus = statusByName[serverConfig.name]
            McpEntry(
                name = serverConfig.name,
                config = serverConfig,
                connectionStatus = runtimeStatus?.status,
                connectionError = runtimeStatus?.error,
                isLoading = serverConfig.name in loadingNames,
            )
        }

        return ApiResult.Success(entries)
    }

    /**
     * Connects the named MCP server, then refreshes and returns the updated entry list.
     * [McpEntry.isLoading] will be true for [name] during the call.
     */
    fun connect(port: Int, name: String): ApiResult<List<McpEntry>> {
        loadingNames.add(name)
        return try {
            val toggleResult = mcpClient.connect(port, name)
            if (toggleResult is ApiResult.Failure) return toggleResult
            listMcpEntries(port)
        } finally {
            loadingNames.remove(name)
        }
    }

    /**
     * Disconnects the named MCP server, then refreshes and returns the updated entry list.
     * [McpEntry.isLoading] will be true for [name] during the call.
     */
    fun disconnect(port: Int, name: String): ApiResult<List<McpEntry>> {
        loadingNames.add(name)
        return try {
            val toggleResult = mcpClient.disconnect(port, name)
            if (toggleResult is ApiResult.Failure) return toggleResult
            listMcpEntries(port)
        } finally {
            loadingNames.remove(name)
        }
    }
}
