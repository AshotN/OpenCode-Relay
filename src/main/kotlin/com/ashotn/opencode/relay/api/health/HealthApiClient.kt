package com.ashotn.opencode.relay.api.health

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport

class HealthApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(
        defaultConnectTimeoutMs = 1_000,
        defaultReadTimeoutMs = 1_000,
    ),
) {
    fun isHealthy(port: Int): ApiResult<Boolean> {
        val endpoint = HealthEndpoints.check()
        return when (val result = transport.get(port = port, path = endpoint.path)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> ApiResult.Success(true)
        }
    }
}
