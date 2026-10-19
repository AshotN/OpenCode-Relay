package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object SessionEndpoints {
    fun create(): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/api/session")

    fun prompt(sessionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/api/session/$sessionId/prompt")

    fun list(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/api/session")

    /**
     * Diff of the newest user turn. v2 defaults `from` to the turn of the newest
     * user message, so no query is needed.
     */
    fun diffLatestTurn(sessionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.GET, path = "/api/session/$sessionId/diff")

    /** Diff of the turn that starts at the given user message. */
    fun diffTurn(sessionId: String, fromUserMessageId: String): ApiEndpoint {
        val query = "?from=${encode(fromUserMessageId)}"
        return ApiEndpoint(method = HttpMethod.GET, path = "/api/session/$sessionId/diff$query")
    }

    /** Diff of the range of user-message turns from [fromUserMessageId] through [toUserMessageId]. */
    fun diffRange(
        sessionId: String,
        fromUserMessageId: String,
        toUserMessageId: String,
    ): ApiEndpoint {
        val query = "?from=${encode(fromUserMessageId)}&to=${encode(toUserMessageId)}"
        return ApiEndpoint(method = HttpMethod.GET, path = "/api/session/$sessionId/diff$query")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    fun messages(sessionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.GET, path = "/api/session/$sessionId/message")

    fun delete(sessionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.DELETE, path = "/api/session/$sessionId")

    fun update(sessionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.PATCH, path = "/api/session/$sessionId")
}
