package com.ashotn.opencode.relay.api.mcp

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.flatMap
import com.ashotn.opencode.relay.api.transport.map
import com.ashotn.opencode.relay.api.transport.parseBooleanResponse
import com.ashotn.opencode.relay.api.transport.parseJsonEnvelope
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.ashotn.opencode.relay.util.getStringOrNull
import com.google.gson.JsonObject

class McpApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    data class McpServer(
        val name: String,
        val status: McpConnectionStatus,
        /** Present when status is [McpConnectionStatus.FAILED] or [McpConnectionStatus.NEEDS_AUTH]. */
        val error: String? = null,
    )

    enum class McpConnectionStatus {
        CONNECTED,
        PENDING,
        DISABLED,
        FAILED,
        NEEDS_AUTH,
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
        // v2 returns {location, data: [Mcp.Server]}; the composite envelope keeps
        // `data` alongside sibling fields, so it is not unwrapped automatically.
        return transport.parseJsonEnvelope(response)
            .map { root -> root.get("data") }
            .flatMap { dataElement ->
                val servers = if (dataElement != null && dataElement.isJsonArray) {
                    dataElement.asJsonArray.mapNotNull { element ->
                        if (!element.isJsonObject) return@mapNotNull null
                        parseServer(element.asJsonObject)
                    }
                } else {
                    emptyList()
                }
                ApiResult.Success(servers)
            }
            .withParseContext(endpoint)
    }

    private fun parseServer(obj: JsonObject): McpServer? {
        val name = obj.getStringOrNull("name") ?: return null
        val statusObj = obj.get("status")
        if (statusObj == null) return McpServer(name = name, status = McpConnectionStatus.UNKNOWN)

        // v2 status is a nested object: {status: "...", error?}.
        val statusRaw = if (statusObj.isJsonObject) {
            statusObj.asJsonObject.getStringOrNull("status")
        } else {
            statusObj.takeIf { it.isJsonPrimitive }?.asString
        }
        val error = if (statusObj.isJsonObject) {
            statusObj.asJsonObject.getStringOrNull("error")
        } else {
            null
        }
        val status = when (statusRaw) {
            "connected" -> McpConnectionStatus.CONNECTED
            "pending" -> McpConnectionStatus.PENDING
            "disabled" -> McpConnectionStatus.DISABLED
            "failed" -> McpConnectionStatus.FAILED
            "needs_auth" -> McpConnectionStatus.NEEDS_AUTH
            else -> McpConnectionStatus.UNKNOWN
        }
        return McpServer(name = name, status = status, error = error)
    }
}
