package com.ashotn.opencode.relay.api.permission

import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PermissionApiClientTest {

    @Test
    fun `reply sends decision payload and parses empty 204 as success`() {
        withTestServer { server, port ->
            var requestBody = ""
            server.createContext("/api/session/ses_1/permission/per_1/reply") { exchange ->
                requestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            val client = PermissionApiClient()
            val result = client.reply(port, "ses_1", "per_1", "once")

            val success = assertIs<ApiResult.Success<Boolean>>(result)
            assertEquals(true, success.value)
            assertTrue(requestBody.contains("\"decision\":\"once\""))
        }
    }

    @Test
    fun `reply returns failure for invalid payload`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/permission/per_1/reply") { exchange ->
                val body = "{\"ok\":\"yes\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = PermissionApiClient()
            val result = client.reply(port, "ses_1", "per_1", "once")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals(
                "POST /api/session/ses_1/permission/per_1/reply: Expected boolean permission response payload",
                error.message,
            )
        }
    }

}
