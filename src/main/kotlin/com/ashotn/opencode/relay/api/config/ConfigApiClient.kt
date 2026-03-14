package com.ashotn.opencode.relay.api.config

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.mapJsonObjectResponse
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

    fun getMcpServers(port: Int): ApiResult<List<McpServerConfig>> {
        val endpoint = ConfigEndpoints.get()
        val response = transport.get(port = port, path = endpoint.path)
        return transport.mapJsonObjectResponse(response) { root: JsonObject ->
            val mcpObj = root.getObjectOrNull("mcp") ?: return@mapJsonObjectResponse ApiResult.Success(emptyList())
            val servers = mcpObj.entrySet().mapNotNull { entry ->
                val name = entry.key
                val element = entry.value
                if (!element.isJsonObject) return@mapNotNull null
                val obj = element.asJsonObject
                val enabled = obj.get("enabled")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asBoolean
                    ?: true

                when (obj.getStringOrNull("type")) {
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
                        val url = obj.getStringOrNull("url") ?: return@mapNotNull null
                        McpServerConfig.Remote(
                            name = name,
                            url = url,
                            enabled = enabled,
                        )
                    }

                    else -> null
                }
            }
            ApiResult.Success(servers)
        }.withParseContext(endpoint)
    }
}
