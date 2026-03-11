package com.ashotn.opencode.companion.api.mcp

import com.ashotn.opencode.companion.api.transport.ApiResult
import com.ashotn.opencode.companion.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.companion.api.transport.mapJsonObjectResponse
import com.ashotn.opencode.companion.api.transport.parseBooleanResponse
import com.ashotn.opencode.companion.api.transport.withParseContext
import com.ashotn.opencode.companion.util.getStringOrNull
import com.google.gson.JsonObject

class McpApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    data class McpServer(
        val name: String,
        val status: McpConnectionStatus,
        /** Present when status is [McpConnectionStatus.FAILED] or [McpConnectionStatus.NEEDS_CLIENT_REGISTRATION]. */
        val error: String? = null,
    )

    enum class McpConnectionStatus {
        CONNECTED,
        DISABLED,
        FAILED,
        NEEDS_AUTH,
        NEEDS_CLIENT_REGISTRATION,
        UNKNOWN,
    }

    fun connect(port: Int, name: String): ApiResult<Boolean> {
        val endpoint = McpEndpoints.connect(name)
        val response = transport.post(port = port, path = endpoint.path, payload = "{}")
        return transport.parseBooleanResponse(response, emptyBodyValue = true)
            .withParseContext(endpoint)
    }

    fun disconnect(port: Int, name: String): ApiResult<Boolean> {
        val endpoint = McpEndpoints.disconnect(name)
        val response = transport.post(port = port, path = endpoint.path, payload = "{}")
        return transport.parseBooleanResponse(response, emptyBodyValue = true)
            .withParseContext(endpoint)
    }

    fun listServers(port: Int): ApiResult<List<McpServer>> {
        val endpoint = McpEndpoints.status()
        val response = transport.get(port = port, path = endpoint.path)
        return transport.mapJsonObjectResponse(response) { root: JsonObject ->
            val servers = root.entrySet().map { entry ->
                val name = entry.key
                val element = entry.value
                if (!element.isJsonObject) {
                    return@map McpServer(name = name, status = McpConnectionStatus.UNKNOWN)
                }
                val obj = element.asJsonObject
                val statusRaw = obj.getStringOrNull("status")
                val error = obj.getStringOrNull("error")
                val status = when (statusRaw) {
                    "connected" -> McpConnectionStatus.CONNECTED
                    "disabled" -> McpConnectionStatus.DISABLED
                    "failed" -> McpConnectionStatus.FAILED
                    "needs_auth" -> McpConnectionStatus.NEEDS_AUTH
                    "needs_client_registration" -> McpConnectionStatus.NEEDS_CLIENT_REGISTRATION
                    else -> McpConnectionStatus.UNKNOWN
                }
                McpServer(name = name, status = status, error = error)
            }
            ApiResult.Success(servers)
        }.withParseContext(endpoint)
    }
}
