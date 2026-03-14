package com.ashotn.opencode.relay.api.config

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.HttpMethod

internal object ConfigEndpoints {
    fun get(): ApiEndpoint = ApiEndpoint(method = HttpMethod.GET, path = "/config")
}
