package com.ashotn.opencode.api.health

import com.ashotn.opencode.api.transport.ApiEndpoint
import com.ashotn.opencode.api.transport.HttpMethod

internal object HealthEndpoints {
    fun check(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/global/health")
}
