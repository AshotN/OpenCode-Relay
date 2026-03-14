package com.ashotn.opencode.relay.api.health

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod

internal object HealthEndpoints {
    fun check(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/global/health")
}
