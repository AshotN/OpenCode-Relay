package com.ashotn.opencode.relay.api.tui

import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TuiApiClientTest {

    @Test
    fun `appendPrompt parses boolean response and sends text payload`() {
        withTestServer { server, port ->
            var method = ""
            var bodyReceived = ""
            server.createContext("/tui/append-prompt") { exchange ->
                method = exchange.requestMethod
                bodyReceived = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val body = "true"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = TuiApiClient()
            val result = client.appendPrompt(port, "hello")

            val success = assertIs<ApiResult.Success<Boolean>>(result)
            assertEquals(true, success.value)
            assertEquals("POST", method)
            assertTrue(bodyReceived.contains("\"text\":\"hello\""))
        }
    }

    @Test
    fun `selectSession parses boolean response and sends sessionID`() {
        withTestServer { server, port ->
            var method = ""
            var bodyReceived = ""
            server.createContext("/tui/select-session") { exchange ->
                method = exchange.requestMethod
                bodyReceived = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val body = "true"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = TuiApiClient()
            val result = client.selectSession(port, "ses_1")

            val success = assertIs<ApiResult.Success<Boolean>>(result)
            assertEquals(true, success.value)
            assertEquals("POST", method)
            assertTrue(bodyReceived.contains("\"sessionID\":\"ses_1\""))
        }
    }

    @Test
    fun `returns failure for non-boolean payload on append`() {
        withTestServer { server, port ->
            server.createContext("/tui/append-prompt") { exchange ->
                val body = "{\"ok\":\"yes\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = TuiApiClient()
            val result = client.appendPrompt(port, "hello")

            assertIs<ApiResult.Failure>(result)
        }
    }

    @Test
    fun `returns failure for unknown success payload on selectSession`() {
        withTestServer { server, port ->
            server.createContext("/tui/select-session") { exchange ->
                val body = "{\"status\":\"ok\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = TuiApiClient()
            val result = client.selectSession(port, "ses_1")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals("POST /tui/select-session: Expected boolean response payload", error.message)
        }
    }

    @Test
    fun `returns failure for empty response body on selectSession`() {
        withTestServer { server, port ->
            server.createContext("/tui/select-session") { exchange ->
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            val client = TuiApiClient()
            val result = client.selectSession(port, "ses_1")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals("POST /tui/select-session: Expected boolean response payload", error.message)
        }
    }

}
