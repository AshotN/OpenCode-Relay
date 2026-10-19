package com.ashotn.opencode.relay.api.health

import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.map
import com.ashotn.opencode.relay.api.transport.parseJsonObjectResponse
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.ashotn.opencode.relay.util.getIntOrNull
import com.ashotn.opencode.relay.util.getStringOrNull
import com.google.gson.JsonObject

class HealthApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(
        defaultConnectTimeoutMs = 1_000,
        defaultReadTimeoutMs = 1_000,
    ),
) {
    /**
     * Fetches `GET /api/info` (the v2 health/readiness endpoint).
     * A 2xx response means the server is up; the payload is parsed into [ServerInfo].
     */
    fun fetchInfo(port: Int): ApiResult<ServerInfo> {
        val endpoint = HealthEndpoints.check()
        val rawResponse = transport.get(port = port, path = endpoint.path)
        return when (val parsed = transport.parseJsonObjectResponse(rawResponse).withParseContext(endpoint)) {
            is ApiResult.Failure -> parsed
            is ApiResult.Success -> parseInfo(parsed.value)
        }
    }

    fun isHealthy(port: Int): ApiResult<Boolean> = fetchInfo(port).map { true }

    private fun parseInfo(info: JsonObject): ApiResult<ServerInfo> {
        val version = info.getStringOrNull("version")
            ?: return ApiResult.Failure(ApiError.ParseError("Missing 'version' in server info"))
        val pid = info.getIntOrNull("pid")
            ?: return ApiResult.Failure(ApiError.ParseError("Missing 'pid' in server info"))
        val urls = info.get("urls")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString }
            .orEmpty()
        return ApiResult.Success(ServerInfo(version = version, pid = pid, urls = urls))
    }
}
