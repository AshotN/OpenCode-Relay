package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object SessionEndpoints {
    fun create(): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/session")


    fun promptAsync(sessionId: String): ApiEndpoint =
        ApiEndpoint(method = HttpMethod.POST, path = "/session/$sessionId/prompt_async")

    fun list(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/session")

    fun diff(sessionId: String, messageId: String): ApiEndpoint {
        val query = "?messageID=${URLEncoder.encode(messageId, StandardCharsets.UTF_8)}"
        return ApiEndpoint(method = HttpMethod.GET, path = "/session/$sessionId/diff$query")
    }

    fun messages(sessionId: String): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/session/$sessionId/message")

    fun delete(sessionId: String): ApiEndpoint = ApiEndpoint(method = HttpMethod.DELETE, path = "/session/$sessionId")

    fun update(sessionId: String): ApiEndpoint = ApiEndpoint(method = HttpMethod.PATCH, path = "/session/$sessionId")
}
