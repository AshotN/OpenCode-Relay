package com.ashotn.opencode.api.transport

fun <T> ApiResult<T>.withParseContext(endpoint: ApiEndpoint): ApiResult<T> =
    withParseContext(endpoint.requestContext)

fun <T> ApiResult<T>.withParseContext(requestContext: String): ApiResult<T> = when (this) {
    is ApiResult.Success -> this
    is ApiResult.Failure -> {
        val parseError = error as? ApiError.ParseError
        if (parseError == null) {
            this
        } else {
            ApiResult.Failure(
                ApiError.ParseError(
                    message = "$requestContext: ${parseError.message}",
                    cause = parseError.cause,
                )
            )
        }
    }
}
