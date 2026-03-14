package com.ashotn.opencode.relay.api.mcp

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod

internal object McpEndpoints {
    fun status(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/mcp")
    fun connect(name: String): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/mcp/$name/connect")
    fun disconnect(name: String): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/mcp/$name/disconnect")
}
