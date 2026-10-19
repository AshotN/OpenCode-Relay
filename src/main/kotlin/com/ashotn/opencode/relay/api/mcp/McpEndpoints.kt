package com.ashotn.opencode.relay.api.mcp

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object McpEndpoints {
    fun status(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/api/mcp")

    fun connect(name: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/api/experimental/mcp/${encode(name)}/connect")

    fun disconnect(name: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/api/experimental/mcp/${encode(name)}/disconnect")

    private fun encode(name: String): String = URLEncoder.encode(name, StandardCharsets.UTF_8)
}
