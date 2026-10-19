package com.ashotn.opencode.relay.api.permission

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.parseBooleanResponse
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.google.gson.JsonObject

class PermissionApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    /**
     * Replies to a pending permission request.
     *
     * v2 uses `POST /api/session/{id}/permission/{requestID}/reply` with a
     * `{"decision": "once"|"always"|"reject"}` body (v1 posted `{"response": ...}` to
     * `/session/{id}/permissions/{id}`).
     */
    fun reply(port: Int, sessionId: String, permissionId: String, response: String): ApiResult<Boolean> {
        val endpoint = PermissionEndpoints.reply(sessionId, permissionId)
        val payload = JsonObject().also { it.addProperty("decision", response) }
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
