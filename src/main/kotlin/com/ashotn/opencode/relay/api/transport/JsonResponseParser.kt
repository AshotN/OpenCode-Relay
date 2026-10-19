package com.ashotn.opencode.relay.api.transport

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * Parses the JSON object payload of a response.
 *
 * Unwraps the v2 `{"data": ...}` envelope when present (see [unwrapData]).
 * Routes returning composite envelopes with sibling fields
 * (e.g. `GET /api/session` -> `{data, cursor}`) should use [parseJsonEnvelope].
 */
fun OpenCodeHttpTransport.parseJsonObjectResponse(
    response: ApiResult<String?>,
): ApiResult<JsonObject> = when (response) {
    is ApiResult.Failure -> response
    is ApiResult.Success -> when (val parsed = parseJsonObject(response.value)) {
        is ApiResult.Failure -> parsed
        is ApiResult.Success -> {
            val payload = parsed.value.unwrapData()
            if (payload.isJsonObject) {
                ApiResult.Success(payload.asJsonObject)
            } else {
                ApiResult.Failure(ApiError.ParseError("Expected JSON object payload"))
            }
        }
    }
}

/**
 * Parses the raw response envelope without unwrapping `data`.
 * Intended for composite v2 envelopes such as `{data, cursor}` or `{location, data}`.
 */
fun OpenCodeHttpTransport.parseJsonEnvelope(
    response: ApiResult<String?>,
): ApiResult<JsonObject> = when (response) {
    is ApiResult.Failure -> response
    is ApiResult.Success -> parseJsonObject(response.value)
}

/**
 * Parses the JSON array payload of a response.
 *
 * In v2, arrays arrive wrapped as `{"data": [...]}`. A bare array at the root
 * is accepted defensively.
 */
fun OpenCodeHttpTransport.parseJsonArrayResponse(
    response: ApiResult<String?>,
): ApiResult<JsonArray> = when (response) {
    is ApiResult.Failure -> response
    is ApiResult.Success -> when (val rootResult = parseJsonElement(response.value)) {
        is ApiResult.Failure -> rootResult
        is ApiResult.Success -> {
            val payload = rootResult.value.unwrapData()
            if (payload.isJsonArray) {
                ApiResult.Success(payload.asJsonArray)
            } else {
                ApiResult.Failure(ApiError.ParseError("Expected JSON array payload"))
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
