package com.ashotn.opencode.relay.api.transport

fun OpenCodeHttpTransport.parseBooleanResponse(
    response: ApiResult<String?>,
    emptyBodyValue: Boolean? = null,
    errorMessage: String = "Expected boolean response payload",
): ApiResult<Boolean> = when (response) {
    is ApiResult.Failure -> response
    is ApiResult.Success -> {
        val body = response.value
        if (body.isNullOrBlank()) {
            if (emptyBodyValue != null) {
                ApiResult.Success(emptyBodyValue)
            } else {
                ApiResult.Failure(ApiError.ParseError(errorMessage))
            }
        } else {
            when (val elementResult = parseJsonElement(body)) {
                is ApiResult.Failure -> elementResult
                is ApiResult.Success -> {
                    val payload = elementResult.value.unwrapData()
                    if (payload.isJsonPrimitive && payload.asJsonPrimitive.isBoolean) {
                        ApiResult.Success(payload.asBoolean)
                    } else {
                        ApiResult.Failure(ApiError.ParseError(errorMessage))
                    }
                }
            }
        }
    }
}
