package com.ashotn.opencode.relay.api.health

import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HealthApiClientTest {

    @Test
    fun `isHealthy returns success true on 2xx`() {
        withTestServer { server, port ->
            server.createContext("/api/info") { exchange ->
                val body = """{"version":"2.0.32","pid":42,"urls":["http://127.0.0.1:4096"]}"""
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
            server.createContext("/api/info") { exchange ->
                val body = "down"
                exchange.sendResponseHeaders(503, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            val result = client.isHealthy(port)

            assertIs<ApiResult.Failure>(result)
        }
    }

    @Test
    fun `fetchInfo parses server info payload`() {
        withTestServer { server, port ->
            server.createContext("/api/info") { exchange ->
                val body = """{"version":"2.0.32","pid":42,"urls":["http://127.0.0.1:4096"],"paths":{"tmp":"/tmp"}}"""
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            val result = client.fetchInfo(port)

            val success = assertIs<ApiResult.Success<ServerInfo>>(result)
            assertEquals("2.0.32", success.value.version)
            assertEquals(42, success.value.pid)
            assertEquals(listOf("http://127.0.0.1:4096"), success.value.urls)
        }
    }

    @Test
    fun `fetchInfo fails when required fields are missing`() {
        withTestServer { server, port ->
            server.createContext("/api/info") { exchange ->
                val body = """{"urls":["http://127.0.0.1:4096"]}"""
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = HealthApiClient()
            val result = client.fetchInfo(port)

            assertIs<ApiResult.Failure>(result)
        }
    }
}
