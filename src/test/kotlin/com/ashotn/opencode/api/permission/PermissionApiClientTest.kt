package com.ashotn.opencode.api.permission

import com.ashotn.opencode.api.transport.ApiError
import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PermissionApiClientTest {

    @Test
    fun `reply sends response payload and parses boolean`() {
        withTestServer { server, port ->
            var requestBody = ""
            server.createContext("/session/ses_1/permissions/per_1") { exchange ->
                requestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val body = "true"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = PermissionApiClient()
            val result = client.reply(port, "ses_1", "per_1", "allow")

            val success = assertIs<ApiResult.Success<Boolean>>(result)
            assertEquals(true, success.value)
            assertTrue(requestBody.contains("\"response\":\"allow\""))
        }
    }

    @Test
    fun `reply returns failure for invalid payload`() {
        withTestServer { server, port ->
            server.createContext("/session/ses_1/permissions/per_1") { exchange ->
                val body = "{\"ok\":\"yes\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = PermissionApiClient()
            val result = client.reply(port, "ses_1", "per_1", "allow")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals(
                "POST /session/ses_1/permissions/per_1: Expected boolean permission response payload",
                error.message,
            )
        }
    }

}
