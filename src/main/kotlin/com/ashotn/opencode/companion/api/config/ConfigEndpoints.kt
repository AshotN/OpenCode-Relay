package com.ashotn.opencode.companion.api.config

import com.ashotn.opencode.companion.api.transport.ApiEndpoint
import com.ashotn.opencode.companion.api.transport.HttpMethod

internal object ConfigEndpoints {
    fun get(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/config")
}
