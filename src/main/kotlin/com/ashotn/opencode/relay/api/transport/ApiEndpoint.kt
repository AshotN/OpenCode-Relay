package com.ashotn.opencode.relay.api.transport

enum class HttpMethod {
    GET,
    POST,
    DELETE,
    PATCH,
}

data class ApiEndpoint(
    val method: HttpMethod,
    val path: String,
) {
    val requestContext: String
        get() = "${method.name} $path"
}
