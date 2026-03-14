package com.ashotn.opencode.relay.api.tui

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.parseBooleanResponse
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.google.gson.JsonObject

class TuiApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    fun appendPrompt(port: Int, text: String): ApiResult<Boolean> {
        val endpoint = TuiEndpoints.appendPrompt()
        val payload = JsonObject().also { it.addProperty("text", text) }
        val response = transport.post(port = port, path = endpoint.path, payload = payload.toString())
        return transport.parseBooleanResponse(response).withParseContext(endpoint)
    }

    fun selectSession(port: Int, sessionId: String): ApiResult<Boolean> {
        val endpoint = TuiEndpoints.selectSession()
        val payload = JsonObject().also { it.addProperty("sessionID", sessionId) }
        val response = transport.post(port = port, path = endpoint.path, payload = payload.toString())
        return transport.parseBooleanResponse(response).withParseContext(endpoint)
    }
}
