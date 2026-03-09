package com.ashotn.opencode.companion.api.session

import com.ashotn.opencode.companion.api.transport.ApiEndpoint
import com.ashotn.opencode.companion.api.transport.HttpMethod

internal object SessionEndpoints {
    fun create(): ApiEndpoint = ApiEndpoint(method = HttpMethod.POST, path = "/session")

    fun list(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/session")

    fun diff(sessionId: String): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/session/$sessionId/diff")
}
