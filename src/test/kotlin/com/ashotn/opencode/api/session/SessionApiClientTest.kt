package com.ashotn.opencode.api.session

import com.ashotn.opencode.api.transport.ApiError
import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.withTestServer
import com.ashotn.opencode.ipc.OpenCodeEvent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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

            val success = assertIs<ApiResult.Success<OpenCodeEvent.SessionDiff>>(result)
            assertEquals("ses_1", success.value.sessionId)
            assertEquals(1, success.value.files.size)
            assertEquals("a.txt", success.value.files.first().file)
        }
    }

    @Test
    fun `fetchSessionDiffSnapshot returns empty event for empty body`() {
        withTestServer { server, port ->
            server.createContext("/session/ses_1/diff") { exchange ->
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            val client = SessionApiClient()
            val result = client.fetchSessionDiffSnapshot(port, "ses_1")

            val success = assertIs<ApiResult.Success<OpenCodeEvent.SessionDiff>>(result)
            assertEquals("ses_1", success.value.sessionId)
            assertEquals(0, success.value.files.size)
        }
    }

    @Test
    fun `fetchSessionDiffSnapshot returns failure on malformed array payload`() {
        withTestServer { server, port ->
            server.createContext("/session/ses_1/diff") { exchange ->
                val body = "{\"not\":\"array\"}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionDiffSnapshot(port, "ses_1")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals("GET /session/ses_1/diff: Expected JSON array", error.message)
        }
    }

    @Test
    fun `fetchFileDiffPreview returns matching diff preview`() {
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
            val result = client.fetchFileDiffPreview(port, "ses_1", "/repo", "/repo/a.txt")

            val success = assertIs<ApiResult.Success<SessionApiClient.FileDiffPreview?>>(result)
            assertEquals("old", success.value?.before)
            assertEquals("new", success.value?.after)
        }
    }

    @Test
    fun `fetchFileDiffPreview returns success null when file is not in diff`() {
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
            val result = client.fetchFileDiffPreview(port, "ses_1", "/repo", "/repo/missing.txt")

            val success = assertIs<ApiResult.Success<SessionApiClient.FileDiffPreview?>>(result)
            assertNull(success.value)
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

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals("GET /session: Expected JSON array", error.message)
        }
    }

}
