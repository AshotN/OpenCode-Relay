package com.ashotn.opencode.companion.api.transport

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

class OpenCodeHttpTransport(
    private val defaultConnectTimeoutMs: Int = 3_000,
    private val defaultReadTimeoutMs: Int = 5_000,
) {
    data class Timeouts(
        val connectTimeoutMs: Int,
        val readTimeoutMs: Int,
    )

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
        val resolvedTimeouts = timeouts ?: Timeouts(defaultConnectTimeoutMs, defaultReadTimeoutMs)

        val connection = try {
            openConnection(port, path)
        } catch (e: IllegalArgumentException) {
            return ApiResult.Failure(ApiError.NetworkError("Invalid request URL", e))
        } catch (e: IOException) {
            return ApiResult.Failure(ApiError.NetworkError(e.message ?: "Network I/O error", e))
        }

        return try {
            connection.requestMethod = method
            connection.connectTimeout = resolvedTimeouts.connectTimeoutMs
            connection.readTimeout = resolvedTimeouts.readTimeoutMs
            connection.setRequestProperty("Accept", accept)

            if (payload != null) {
                connection.doOutput = true
                if (contentType != null) {
                    connection.setRequestProperty("Content-Type", contentType)
                }
                connection.outputStream.use { out ->
                    out.write(payload.toByteArray(Charsets.UTF_8))
                }
            }

            val statusCode = connection.responseCode
            val body = readBody(connection, statusCode)
            if (statusCode in 200..299) {
                ApiResult.Success(body)
            } else {
                ApiResult.Failure(ApiError.HttpError(statusCode, body))
            }
        } catch (e: SocketTimeoutException) {
            ApiResult.Failure(ApiError.NetworkError("Request timed out", e))
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.NetworkError(e.message ?: "Network I/O error", e))
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(port: Int, path: String): HttpURLConnection {
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        val url = URI("http://localhost:$port$normalizedPath").toURL()
        return url.openConnection() as HttpURLConnection
    }

    private fun readBody(connection: HttpURLConnection, statusCode: Int): String? {
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
    }

    companion object {
        const val APPLICATION_JSON = "application/json"
    }
}
