package com.ashotn.opencode.api.session

import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionApiClientTest {

    @Test
    fun `createSession parses id field`() {
        withTestServer { server, port ->
            server.createContext("/session") { exchange ->
                val body = "{\"id\":\"ses_123\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.createSession(port)

            val success = assertIs<ApiResult.Success<SessionApiClient.CreatedSession>>(result)
            assertEquals("ses_123", success.value.sessionId)
        }
    }

    @Test
    fun `createSession returns failure when id field is missing`() {
        withTestServer { server, port ->
            server.createContext("/session") { exchange ->
                val body = "{\"sessionID\":\"ses_456\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.createSession(port)

            assertIs<ApiResult.Failure>(result)
        }
    }

    @Test
    fun `fetchSessionDiffSnapshot parses diff files`() {
        withTestServer { server, port ->
            server.createContext("/session/ses_1/diff") { exchange ->
                val body = """
                    [
                      {
                        "file": "a.txt",
                        "before": "old",
                        "after": "new",
                        "additions": 1,
                        "deletions": 1,
                        "status": "modified"
                      }
                    ]
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionDiffSnapshot(port, "ses_1")

            assertTrue(result.success)
            assertEquals("ses_1", result.event?.sessionId)
            assertEquals(1, result.event?.files?.size)
            assertEquals("a.txt", result.event?.files?.first()?.file)
        }
    }

    @Test
    fun `fetchSessionHierarchy projects metadata`() {
        withTestServer { server, port ->
            server.createContext("/session") { exchange ->
                val body = """
                    [
                      {
                        "id": "ses_1",
                        "title": "Root",
                        "description": "desc",
                        "time": { "updated": 123 }
                      },
                      {
                        "id": "ses_2",
                        "parentID": "ses_1",
                        "name": "Child"
                      }
                    ]
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionHierarchy(port)

            val success = assertIs<ApiResult.Success<SessionApiClient.HierarchySnapshot>>(result)
            assertEquals(setOf("ses_1", "ses_2"), success.value.sessionIds)
            assertEquals("ses_1", success.value.parentBySessionId["ses_2"])
            assertEquals("Root", success.value.titleBySessionId["ses_1"])
            assertEquals("desc", success.value.descriptionBySessionId["ses_1"])
            assertEquals(123L, success.value.updatedAtBySessionId["ses_1"])
        }
    }

    @Test
    fun `createSession returns failure on non-2xx`() {
        withTestServer { server, port ->
            server.createContext("/session") { exchange ->
                val body = "{\"error\":\"boom\"}"
                exchange.sendResponseHeaders(500, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.createSession(port)

            assertIs<ApiResult.Failure>(result)
        }
    }

    @Test
    fun `fetchSessionHierarchy returns failure on malformed payload`() {
        withTestServer { server, port ->
            server.createContext("/session") { exchange ->
                val body = "{\"not\":\"an array\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionHierarchy(port)

            assertIs<ApiResult.Failure>(result)
        }
    }

}
