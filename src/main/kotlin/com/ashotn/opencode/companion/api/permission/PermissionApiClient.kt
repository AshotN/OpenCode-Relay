package com.ashotn.opencode.companion.api.permission

import com.ashotn.opencode.companion.api.transport.ApiResult
import com.ashotn.opencode.companion.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.companion.api.transport.parseBooleanResponse
import com.ashotn.opencode.companion.api.transport.withParseContext
import com.google.gson.JsonObject

class PermissionApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    fun reply(port: Int, sessionId: String, permissionId: String, response: String): ApiResult<Boolean> {
        val endpoint = PermissionEndpoints.reply(sessionId, permissionId)
        val payload = JsonObject().also { it.addProperty("response", response) }
        val rawResponse = transport.post(
            port = port,
            path = endpoint.path,
            payload = payload.toString(),
        )
        return transport.parseBooleanResponse(
            response = rawResponse,
            emptyBodyValue = true,
            errorMessage = "Expected boolean permission response payload",
        ).withParseContext(endpoint)
    }
}
