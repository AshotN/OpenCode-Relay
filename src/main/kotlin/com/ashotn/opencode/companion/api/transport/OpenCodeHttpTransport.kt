package com.ashotn.opencode.companion.api.transport

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OpenCodeHttpTransport(
    defaultConnectTimeoutMs: Int = 3_000,
    private val defaultReadTimeoutMs: Int = 5_000,
) {
    data class Timeouts(val readTimeoutMs: Int)

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(defaultConnectTimeoutMs.toLong()))
        .build()

    fun get(
        port: Int,
        path: String,
        timeouts: Timeouts? = null,
        accept: String = APPLICATION_JSON,
    ): ApiResult<String?> = execute(
        method = "GET",
        port = port,
        path = path,
        payload = null,
        contentType = null,
        accept = accept,
        timeouts = timeouts,
    )

    fun post(
        port: Int,
        path: String,
        payload: String,
        timeouts: Timeouts? = null,
        accept: String = APPLICATION_JSON,
    ): ApiResult<String?> = execute(
        method = "POST",
        port = port,
        path = path,
        payload = payload,
        contentType = APPLICATION_JSON,
        accept = accept,
        timeouts = timeouts,
    )

    fun patch(
        port: Int,
        path: String,
        payload: String,
        timeouts: Timeouts? = null,
        accept: String = APPLICATION_JSON,
    ): ApiResult<String?> = execute(
        method = "PATCH",
        port = port,
        path = path,
        payload = payload,
        contentType = APPLICATION_JSON,
        accept = accept,
        timeouts = timeouts,
    )

    fun delete(
        port: Int,
        path: String,
        timeouts: Timeouts? = null,
        accept: String = APPLICATION_JSON,
    ): ApiResult<String?> = execute(
        method = "DELETE",
        port = port,
        path = path,
        payload = null,
        contentType = null,
        accept = accept,
        timeouts = timeouts,
    )

    fun parseJsonObject(body: String?): ApiResult<JsonObject> {
        if (body.isNullOrBlank()) {
            return ApiResult.Failure(ApiError.ParseError("Response body is empty"))
        }
        return try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) {
                ApiResult.Failure(ApiError.ParseError("Expected JSON object"))
            } else {
                ApiResult.Success(root.asJsonObject)
            }
        } catch (e: Exception) {
            ApiResult.Failure(ApiError.ParseError("Malformed JSON", e))
        }
    }

    fun parseJsonElement(body: String?): ApiResult<JsonElement> {
        if (body.isNullOrBlank()) {
            return ApiResult.Failure(ApiError.ParseError("Response body is empty"))
        }
        return try {
            ApiResult.Success(JsonParser.parseString(body))
        } catch (e: Exception) {
            ApiResult.Failure(ApiError.ParseError("Malformed JSON", e))
        }
    }

    private fun execute(
        method: String,
        port: Int,
        path: String,
        payload: String?,
        contentType: String?,
        accept: String,
        timeouts: Timeouts?,
    ): ApiResult<String?> {
        val resolvedTimeouts = timeouts ?: Timeouts(defaultReadTimeoutMs)
        val normalizedPath = if (path.startsWith('/')) path else "/$path"

        val uri = try {
            URI("http://localhost:$port$normalizedPath")
        } catch (e: IllegalArgumentException) {
            return ApiResult.Failure(ApiError.NetworkError("Invalid request URL", e))
        }

        val bodyPublisher = if (payload != null)
            HttpRequest.BodyPublishers.ofString(payload, Charsets.UTF_8)
        else
            HttpRequest.BodyPublishers.noBody()

        val request = HttpRequest.newBuilder(uri)
            .method(method, bodyPublisher)
            .apply {
                header("Accept", accept)
                if (payload != null && contentType != null) header("Content-Type", contentType)
                timeout(Duration.ofMillis(resolvedTimeouts.readTimeoutMs.toLong()))
            }
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val statusCode = response.statusCode()
            val body = response.body().takeIf { it.isNotEmpty() }
            if (statusCode in 200..299) {
                ApiResult.Success(body)
            } else {
                ApiResult.Failure(ApiError.HttpError(statusCode, body))
            }
        } catch (e: java.net.http.HttpTimeoutException) {
            ApiResult.Failure(ApiError.NetworkError("Request timed out", e))
        } catch (e: java.io.IOException) {
            ApiResult.Failure(ApiError.NetworkError(e.message ?: "Network I/O error", e))
        }
    }

    companion object {
        const val APPLICATION_JSON = "application/json"
    }
}
