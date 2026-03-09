package com.ashotn.opencode.companion.api.health

import com.ashotn.opencode.companion.api.transport.ApiEndpoint
import com.ashotn.opencode.companion.api.transport.HttpMethod

internal object HealthEndpoints {
    fun check(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/global/health")
}
