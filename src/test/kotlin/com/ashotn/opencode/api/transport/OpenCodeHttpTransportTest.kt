package com.ashotn.opencode.api.transport

import com.ashotn.opencode.api.withTestServer
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCodeHttpTransportTest {

    @Test
    fun `get returns success for 2xx response`() {
        withTestServer { server, port ->
            server.createContext("/ok") { exchange ->
                val body = "{\"status\":\"ok\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport()
            val result = transport.get(port, "/ok")

            val success = assertIs<ApiResult.Success<String?>>(result)
            assertEquals("{\"status\":\"ok\"}", success.value)
        }
    }

    @Test
    fun `maps non-2xx to http error with body`() {
        withTestServer { server, port ->
            server.createContext("/boom") { exchange ->
                val body = "{\"error\":\"bad request\"}"
                exchange.sendResponseHeaders(400, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport()
            val result = transport.get(port, "/boom")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.HttpError>(failure.error)
            assertEquals(400, error.statusCode)
            assertEquals("{\"error\":\"bad request\"}", error.body)
        }
    }

    @Test
    fun `postJson sends json body and headers`() {
        withTestServer { server, port ->
            var capturedMethod = ""
            var capturedAccept = ""
            var capturedContentType = ""
            var capturedBody = ""
            server.createContext("/submit") { exchange ->
                capturedMethod = exchange.requestMethod
                capturedAccept = exchange.requestHeaders.getFirst("Accept") ?: ""
                capturedContentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
                capturedBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }

                val response = "true"
                exchange.sendResponseHeaders(200, response.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(response.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport()
            val result = transport.postJson(port, "/submit", "{\"text\":\"hello\"}")

            assertIs<ApiResult.Success<String?>>(result)
            assertEquals("POST", capturedMethod)
            assertEquals("application/json", capturedAccept)
            assertEquals("application/json", capturedContentType)
            assertEquals("{\"text\":\"hello\"}", capturedBody)
        }
    }

    @Test
    fun `maps malformed json parsing to parse error`() {
        val transport = OpenCodeHttpTransport()

        val result = transport.parseJsonObject("this is not json")

        val failure = assertIs<ApiResult.Failure>(result)
        assertIs<ApiError.ParseError>(failure.error)
    }

    @Test
    fun `parseJsonElement parses valid json array`() {
        val transport = OpenCodeHttpTransport()

        val result = transport.parseJsonElement("[1,2,3]")

        val success = assertIs<ApiResult.Success<com.google.gson.JsonElement>>(result)
        assertTrue(success.value.isJsonArray)
    }

    @Test
    fun `maps timeout override to network error`() {
        withTestServer { server, port ->
            server.createContext("/slow") { exchange ->
                Thread.sleep(300)
                val body = "{}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val transport = OpenCodeHttpTransport(defaultConnectTimeoutMs = 3_000, defaultReadTimeoutMs = 3_000)
            val result = transport.get(
                port = port,
                path = "/slow",
                timeouts = OpenCodeHttpTransport.Timeouts(connectTimeoutMs = 75, readTimeoutMs = 75),
            )

            val failure = assertIs<ApiResult.Failure>(result)
            assertIs<ApiError.NetworkError>(failure.error)
        }
    }

    @Test
    fun `maps connection refused to network error`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val transport = OpenCodeHttpTransport(defaultConnectTimeoutMs = 50, defaultReadTimeoutMs = 50)

        val result = transport.get(closedPort, "/health")

        val failure = assertIs<ApiResult.Failure>(result)
        val error = assertIs<ApiError.NetworkError>(failure.error)
        assertTrue(error.message.isNotBlank())
    }

}
