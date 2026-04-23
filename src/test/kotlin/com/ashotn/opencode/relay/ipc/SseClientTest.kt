package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
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
}
