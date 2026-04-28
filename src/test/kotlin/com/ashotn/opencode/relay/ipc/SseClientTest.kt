package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SseClientTest {

    @Test
    fun `reports authentication failure once and stops retrying`() {
        withTestServer { server, port ->
            val requests = AtomicInteger(0)
            server.createContext("/event") { exchange ->
                requests.incrementAndGet()
                val body = "Unauthorized"
                exchange.sendResponseHeaders(401, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val authFailure = CountDownLatch(1)
            val client = SseClient(
                port = port,
                onEvent = { },
                onAuthenticationFailure = { authFailure.countDown() },
            )

            try {
                client.start()
                assertTrue(authFailure.await(2, TimeUnit.SECONDS))

                val requestCountAfterFailure = requests.get()
                Thread.sleep(1_300)

                assertEquals(requestCountAfterFailure, requests.get())
            } finally {
                client.stop()
            }
        }
    }

    @Test
    fun `parses busy retry and idle session status events`() {
        withTestServer { server, port ->
            server.createContext("/event") { exchange ->
                val body = """
                    data: {"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}

                    data: {"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"retry","attempt":1,"message":"retrying","next":123}}}

                    data: {"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent>()
            val latch = CountDownLatch(3)
            val client = SseClient(
                port = port,
                onEvent = { event ->
                    synchronized(received) {
                        received.add(event)
                    }
                    latch.countDown()
                },
            )

            try {
                client.start()
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected three session.status events")
            } finally {
                client.stop()
            }

            val statuses = synchronized(received) {
                received.map { assertIs<OpenCodeEvent.SessionStatus>(it).status }
            }
            assertEquals(
                listOf(
                    OpenCodeEvent.SessionStatusType.BUSY,
                    OpenCodeEvent.SessionStatusType.RETRY,
                    OpenCodeEvent.SessionStatusType.IDLE,
                ),
                statuses,
            )
        }
    }

    @Test
    fun `ignores deprecated session idle event`() {
        withTestServer { server, port ->
            server.createContext("/event") { exchange ->
                val body = """
                    data: {"type":"session.idle","properties":{"sessionID":"ses_1"}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val eventReceived = CountDownLatch(1)
            val client = SseClient(
                port = port,
                onEvent = { eventReceived.countDown() },
            )

            try {
                client.start()
                assertFalse(eventReceived.await(400, TimeUnit.MILLISECONDS))
            } finally {
                client.stop()
            }
        }
    }
}
