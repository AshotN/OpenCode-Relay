package com.ashotn.opencode.api.health

import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.transport.OpenCodeHttpTransport

class HealthApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(
        defaultConnectTimeoutMs = 1_000,
        defaultReadTimeoutMs = 1_000,
    ),
) {
    fun isHealthy(port: Int): Boolean {
        val result = transport.get(port = port, path = "/global/health")
        return result is ApiResult.Success
    }
}
