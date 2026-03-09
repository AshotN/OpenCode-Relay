package com.ashotn.opencode.api.transport

import com.google.gson.JsonArray
import com.google.gson.JsonObject

fun OpenCodeHttpTransport.parseJsonObjectResponse(
    response: ApiResult<String?>,
): ApiResult<JsonObject> = when (response) {
    is ApiResult.Failure -> response
    is ApiResult.Success -> parseJsonObject(response.value)
}

fun OpenCodeHttpTransport.parseJsonArrayResponse(
    response: ApiResult<String?>,
): ApiResult<JsonArray> = when (response) {
    is ApiResult.Failure -> response
    is ApiResult.Success -> when (val rootResult = parseJsonElement(response.value)) {
        is ApiResult.Failure -> rootResult
        is ApiResult.Success -> {
            val root = rootResult.value
            if (root.isJsonArray) {
                ApiResult.Success(root.asJsonArray)
            } else {
                ApiResult.Failure(ApiError.ParseError("Expected JSON array"))
            }
        }
    }
}

inline fun <R> OpenCodeHttpTransport.mapJsonObjectResponse(
    response: ApiResult<String?>,
    transform: (JsonObject) -> ApiResult<R>,
): ApiResult<R> = parseJsonObjectResponse(response).flatMap(transform)

inline fun <R> OpenCodeHttpTransport.mapJsonArrayResponse(
    response: ApiResult<String?>,
    transform: (JsonArray) -> ApiResult<R>,
): ApiResult<R> = parseJsonArrayResponse(response).flatMap(transform)
