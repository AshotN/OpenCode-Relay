package com.ashotn.opencode.relay.api.event

import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventStreamClientTest {

    @Test
    fun `consume throws authentication exception on unauthorized response`() {
        withTestServer { server, port ->
            server.createContext("/event") { exchange ->
                val body = "Unauthorized"
                exchange.sendResponseHeaders(401, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val exception = assertFailsWith<EventStreamClient.AuthenticationException> {
                EventStreamClient().consume(port) { }
            }

            assertTrue(exception.message.orEmpty().contains("401"))
        }
    }
}
