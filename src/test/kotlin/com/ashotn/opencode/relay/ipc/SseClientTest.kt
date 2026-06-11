package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SseClientTest {

    @Test
    fun `reports authentication failure once and stops retrying`() {
        withTestServer { server, port ->
            val requests = AtomicInteger(0)
            server.createContext("/global/event") { exchange ->
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
            server.createContext("/global/event") { exchange ->
                val body = """
                    data: {"directory":"/other","payload":{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}}

                    data: {"directory":"/project/","payload":{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}}

                    data: {"directory":"/project","payload":{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"retry","attempt":1,"message":"retrying","next":123}}}}

                    data: {"directory":"/project","payload":{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"idle"}}}}

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
                directory = "/project",
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
    fun `parses wrapped session lifecycle events`() {
        withTestServer { server, port ->
            server.createContext("/global/event") { exchange ->
                val body = """
                    data: {"directory":"/project","payload":{"type":"session.created","properties":{"info":{"id":"ses_created"}}}}

                    data: {"directory":"/project","payload":{"type":"session.updated","properties":{"sessionID":"ses_updated"}}}

                    data: {"directory":"/project","payload":{"type":"session.deleted","properties":{"sessionID":"ses_deleted"}}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent.SessionLifecycleChanged>()
            val latch = CountDownLatch(3)
            val client = SseClient(
                port = port,
                directory = "/project",
                onEvent = { event ->
                    synchronized(received) {
                        received.add(assertIs<OpenCodeEvent.SessionLifecycleChanged>(event))
                    }
                    latch.countDown()
                },
            )

            try {
                client.start()
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected three session lifecycle events")
            } finally {
                client.stop()
            }

            val sessionIds = synchronized(received) { received.map { it.sessionId } }
            assertEquals(listOf("ses_created", "ses_updated", "ses_deleted"), sessionIds)
        }
    }

    @Test
    fun `parses message diff availability with summary files`() {
        withTestServer { server, port ->
            server.createContext("/global/event") { exchange ->
                val body = """
                    data: {"directory":"/project","payload":{"type":"message.updated","properties":{"sessionID":"ses_1","info":{"id":"msg_1","role":"user","summary":{"diffs":[{"file":"pkg/alpha.py"},{"file":"pkg/bravo.py"}]}}}}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent.MessageDiffAvailable>()
            val latch = CountDownLatch(1)
            val client = SseClient(
                port = port,
                directory = "/project",
                onEvent = { event ->
                    synchronized(received) {
                        received.add(assertIs<OpenCodeEvent.MessageDiffAvailable>(event))
                    }
                    latch.countDown()
                },
            )

            try {
                client.start()
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected message diff event")
            } finally {
                client.stop()
            }

            val event = synchronized(received) { received.single() }
            assertEquals("ses_1", event.sessionId)
            assertEquals("msg_1", event.messageId)
            assertEquals(listOf("pkg/alpha.py", "pkg/bravo.py"), event.files)
        }
    }
}
