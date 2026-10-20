package com.ashotn.opencode.relay.api.config

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.mapJsonArrayResponse
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.ashotn.opencode.relay.util.getObjectOrNull
import com.ashotn.opencode.relay.util.getStringOrNull
import com.google.gson.JsonObject

class ConfigApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    sealed class McpServerConfig {
        abstract val name: String
        abstract val enabled: Boolean

        data class Local(
            override val name: String,
            val command: List<String>,
            val environment: Map<String, String>,
            override val enabled: Boolean,
        ) : McpServerConfig()

        data class Remote(
            override val name: String,
            val url: String,
            override val enabled: Boolean,
        ) : McpServerConfig()
    }

    /**
     * Reads the configured MCP servers from `GET /api/config`.
     *
     * v2 returns a list of layered config documents
     * (`[{"type": "document", "path": ..., "info": {<config>}}]`); MCP servers live in
     * `info.mcp.servers` (falling back to `info.mcp` for configs without the wrapper)
     * and are disabled via a `disabled` flag instead of v1's `enabled` flag.
     * Entries are merged across documents with later documents winning.
     */
    fun getMcpServers(port: Int): ApiResult<List<McpServerConfig>> {
        val endpoint = ConfigEndpoints.get()
        val response = transport.get(port = port, path = endpoint.path)
        return transport.mapJsonArrayResponse(response) { documents ->
            val serversByName = LinkedHashMap<String, McpServerConfig>()
            for (document in documents) {
                if (!document.isJsonObject) continue
                val info = document.asJsonObject.getObjectOrNull("info") ?: continue
                val servers = mcpServersOf(info)
                if (servers == null) continue
                for ((name, config) in servers) {
                    serversByName[name] = config
                }
            }
            ApiResult.Success(serversByName.values.toList())
        }.withParseContext(endpoint)
    }

    private fun mcpServersOf(info: JsonObject): Map<String, McpServerConfig>? {
        val mcpObj = info.getObjectOrNull("mcp") ?: return null
        // v2 wraps the server map in a `servers` key; accept a bare map defensively.
        val serversObj = mcpObj.getObjectOrNull("servers") ?: return mcpServersFromMap(mcpObj)
        return mcpServersFromMap(serversObj)
    }

    private fun mcpServersFromMap(serversObj: JsonObject): Map<String, McpServerConfig> {
        val servers = LinkedHashMap<String, McpServerConfig>()
        for ((name, element) in serversObj.entrySet()) {
            if (!element.isJsonObject) continue
            val config = parseMcpServerConfig(name, element.asJsonObject) ?: continue
            servers[name] = config
        }
        return servers
    }

    private fun parseMcpServerConfig(name: String, obj: JsonObject): McpServerConfig? {
        val enabled = obj.get("disabled")
            ?.takeIf { it.isJsonPrimitive }
            ?.asBoolean
            ?.let { !it }
            ?: true

        return when (obj.getStringOrNull("type")) {
            "local" -> {
                val command = obj.get("command")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
                    ?: emptyList()
                val environment = obj.getObjectOrNull("environment")
                    ?.entrySet()
                    ?.associate { e -> e.key to e.value.asString }
                    ?: emptyMap()
                McpServerConfig.Local(
                    name = name,
                    command = command,
                    environment = environment,
                    enabled = enabled,
                )
            }

            "remote" -> {
                val url = obj.getStringOrNull("url") ?: return null
                McpServerConfig.Remote(
                    name = name,
                    url = url,
                    enabled = enabled,
                )
            }

            else -> null
        }
    }
}
