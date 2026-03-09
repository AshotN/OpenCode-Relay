package com.ashotn.opencode.companion.api.transport

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
                    val element = elementResult.value
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) {
                        ApiResult.Success(element.asBoolean)
                    } else {
                        ApiResult.Failure(ApiError.ParseError(errorMessage))
                    }
                }
            }
        }
    }
}
