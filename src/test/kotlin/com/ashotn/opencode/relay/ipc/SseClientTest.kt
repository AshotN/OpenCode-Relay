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
            server.createContext("/api/event") { exchange ->
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
    fun `parses busy retry and idle session status events with directory filtering`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"session.status","location":{"directory":"/other"},"data":{"sessionID":"ses_1","status":{"type":"busy"}}}

                    data: {"id":"evt_2","created":2,"type":"session.status","location":{"directory":"/project/"},"data":{"sessionID":"ses_1","status":{"type":"busy"}}}

                    data: {"id":"evt_3","created":3,"type":"session.status","location":{"directory":"/project"},"data":{"sessionID":"ses_1","status":{"type":"retry","attempt":1,"message":"retrying","next":123}}}

                    data: {"id":"evt_4","created":4,"type":"session.status","location":{"directory":"/project"},"data":{"sessionID":"ses_1","status":{"type":"idle"}}}

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
    fun `parses session lifecycle events`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"session.created","location":{"directory":"/project"},"data":{"sessionID":"ses_created","projectID":"prj_1","slug":"s","location":{"directory":"/project"}}}

                    data: {"id":"evt_2","created":2,"type":"session.renamed","location":{"directory":"/project"},"data":{"sessionID":"ses_renamed"}}

                    data: {"id":"evt_3","created":3,"type":"session.deleted","location":{"directory":"/project"},"data":{"sessionID":"ses_deleted"}}

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
            assertEquals(listOf("ses_created", "ses_renamed", "ses_deleted"), sessionIds)
        }
    }

    @Test
    fun `maps execution started and finished events to busy status, finished signal, and derived idle`() {
        withTestServer { server, port ->
            // v2 turn stream: v2.0.32 emits no `session.idle` or `session.status`
            // around turn completion, only the execution lifecycle events.
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"session.execution.started","location":{"directory":"/project"},"data":{"sessionID":"ses_1","userMessageID":"msg_u1"}}

                    data: {"id":"evt_2","created":2,"type":"session.execution.succeeded","location":{"directory":"/project"},"data":{"sessionID":"ses_1","assistantMessageID":"msg_a1"}}

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
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected three execution events")
            } finally {
                client.stop()
            }

            val events = synchronized(received) { received.toList() }
            val busy = assertIs<OpenCodeEvent.SessionStatus>(events[0])
            assertEquals(OpenCodeEvent.SessionStatusType.BUSY, busy.status)
            assertEquals("ses_1", busy.sessionId)

            val finished = assertIs<OpenCodeEvent.SessionExecutionFinished>(events[1])
            assertEquals("ses_1", finished.sessionId)

            // Idle is derived from the finished execution because v2 emits no idle event.
            val idle = assertIs<OpenCodeEvent.SessionStatus>(events[2])
            assertEquals(OpenCodeEvent.SessionStatusType.IDLE, idle.status)
            assertEquals("ses_1", idle.sessionId)
        }
    }

    @Test
    fun `still maps explicit session idle event to idle status`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"session.idle","location":{"directory":"/project"},"data":{"sessionID":"ses_1"}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent>()
            val latch = CountDownLatch(1)
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
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected idle event")
            } finally {
                client.stop()
            }

            val events = synchronized(received) { received.toList() }
            val idle = assertIs<OpenCodeEvent.SessionStatus>(events[0])
            assertEquals(OpenCodeEvent.SessionStatusType.IDLE, idle.status)
            assertEquals("ses_1", idle.sessionId)
        }
    }

    @Test
    fun `parses permission asked with v2 action and resources fields`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"permission.asked","location":{"directory":"/project"},"data":{"id":"per_1","sessionID":"ses_1","action":"external_directory","resources":["/etc/*"],"metadata":{"command":"cat /etc/passwd"}}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent.PermissionAsked>()
            val latch = CountDownLatch(1)
            val client = SseClient(
                port = port,
                directory = "/project",
                onEvent = { event ->
                    synchronized(received) {
                        received.add(assertIs<OpenCodeEvent.PermissionAsked>(event))
                    }
                    latch.countDown()
                },
            )

            try {
                client.start()
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected permission asked event")
            } finally {
                client.stop()
            }

            val event = synchronized(received) { received.single() }
            assertEquals("per_1", event.requestId)
            assertEquals("ses_1", event.sessionId)
            assertEquals("external_directory", event.permission)
            assertEquals(listOf("/etc/*"), event.patterns)
            assertEquals(mapOf("command" to "cat /etc/passwd"), event.metadata)
        }
    }

    @Test
    fun `parses permission replied`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"permission.replied","location":{"directory":"/project"},"data":{"sessionID":"ses_1","requestID":"per_1","reply":"always"}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent.PermissionReplied>()
            val latch = CountDownLatch(1)
            val client = SseClient(
                port = port,
                directory = "/project",
                onEvent = { event ->
                    synchronized(received) {
                        received.add(assertIs<OpenCodeEvent.PermissionReplied>(event))
                    }
                    latch.countDown()
                },
            )

            try {
                client.start()
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected permission replied event")
            } finally {
                client.stop()
            }

            val event = synchronized(received) { received.single() }
            assertEquals("per_1", event.requestId)
            assertEquals("ses_1", event.sessionId)
            assertEquals(PermissionReply.ALWAYS, event.reply)
        }
    }

    @Test
    fun `parses mcp status changed`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"mcp.status.changed","location":{"directory":"/project"},"data":{"server":"jetbrains"}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent.McpStatusChanged>()
            val latch = CountDownLatch(1)
            val client = SseClient(
                port = port,
                directory = "/project",
                onEvent = { event ->
                    synchronized(received) {
                        received.add(assertIs<OpenCodeEvent.McpStatusChanged>(event))
                    }
                    latch.countDown()
                },
            )

            try {
                client.start()
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected mcp status changed event")
            } finally {
                client.stop()
            }

            val event = synchronized(received) { received.single() }
            assertEquals("jetbrains", event.serverName)
        }
    }

    @Test
    fun `parses server connected`() {
        withTestServer { server, port ->
            server.createContext("/api/event") { exchange ->
                val body = """
                    data: {"id":"evt_1","created":1,"type":"server.connected","data":{}}

                """.trimIndent()
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            val received = mutableListOf<OpenCodeEvent>()
            val latch = CountDownLatch(1)
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
                assertTrue(latch.await(2, TimeUnit.SECONDS), "expected server connected event")
            } finally {
                client.stop()
            }

            val event = synchronized(received) { received.single() }
            assertIs<OpenCodeEvent.ServerConnected>(event)
        }
    }
}
