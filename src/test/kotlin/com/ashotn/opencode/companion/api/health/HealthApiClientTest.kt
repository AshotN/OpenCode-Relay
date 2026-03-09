package com.ashotn.opencode.companion.api.health

import com.ashotn.opencode.companion.api.transport.ApiResult
import com.ashotn.opencode.companion.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HealthApiClientTest {

    @Test
    fun `isHealthy returns success true on 2xx`() {
        withTestServer { server, port ->
            server.createContext("/global/health") { exchange ->
                val body = "ok"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            val result = client.isHealthy(port)

            val success = assertIs<ApiResult.Success<Boolean>>(result)
            assertEquals(true, success.value)
        }
    }

    @Test
    fun `isHealthy returns failure on non-2xx`() {
        withTestServer { server, port ->
            server.createContext("/global/health") { exchange ->
                val body = "down"
                exchange.sendResponseHeaders(503, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            val result = client.isHealthy(port)

            assertIs<ApiResult.Failure>(result)
        }
    }

}
