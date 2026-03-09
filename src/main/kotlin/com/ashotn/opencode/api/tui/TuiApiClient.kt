package com.ashotn.opencode.api.tui

import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.api.transport.parseBooleanResponse
import com.google.gson.JsonObject

class TuiApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    fun appendPrompt(port: Int, text: String): ApiResult<Boolean> {
        val payload = JsonObject().also { it.addProperty("text", text) }
        val response = transport.postJson(port = port, path = "/tui/append-prompt", payload = payload.toString())
        return transport.parseBooleanResponse(response)
    }

    fun selectSession(port: Int, sessionId: String): ApiResult<Boolean> {
        val payload = JsonObject().also { it.addProperty("sessionID", sessionId) }
        val response = transport.postJson(port = port, path = "/tui/select-session", payload = payload.toString())
        return transport.parseBooleanResponse(response)
    }
}
