package com.ashotn.opencode.api.health

import com.ashotn.opencode.api.withTestServer
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthApiClientTest {

    @Test
    fun `isHealthy returns true on 2xx`() {
        withTestServer { server, port ->
            server.createContext("/global/health") { exchange ->
                val body = "ok"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            assertTrue(client.isHealthy(port))
        }
    }

    @Test
    fun `isHealthy returns false on non-2xx`() {
        withTestServer { server, port ->
            server.createContext("/global/health") { exchange ->
                val body = "down"
                exchange.sendResponseHeaders(503, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            assertFalse(client.isHealthy(port))
        }
    }

}
