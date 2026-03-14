package com.ashotn.opencode.relay.api.transport

sealed interface ApiError {
    data class NetworkError(
        val message: String,
        val cause: Throwable? = null,
    ) : ApiError

    data class HttpError(
        val statusCode: Int,
        val body: String?,
    ) : ApiError

    data class ParseError(
        val message: String,
        val cause: Throwable? = null,
    ) : ApiError
}
