package com.ashotn.opencode.api.permission

import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.api.transport.parseBooleanResponse
import com.google.gson.JsonObject

class PermissionApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    fun reply(port: Int, sessionId: String, permissionId: String, response: String): ApiResult<Boolean> {
        val payload = JsonObject().also { it.addProperty("response", response) }
        val rawResponse = transport.postJson(
            port = port,
            path = "/session/$sessionId/permissions/$permissionId",
            payload = payload.toString(),
        )
        return transport.parseBooleanResponse(
            response = rawResponse,
            emptyBodyValue = true,
            errorMessage = "Expected boolean permission response payload",
        )
    }
}
