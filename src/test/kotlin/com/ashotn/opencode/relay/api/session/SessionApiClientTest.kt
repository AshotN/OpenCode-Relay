package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.withTestServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionApiClientTest {

    @Test
    fun `createSession parses id from data envelope`() {
        withTestServer { server, port ->
            server.createContext("/api/session") { exchange ->
                val body = "{\"data\":{\"id\":\"ses_123\"}}"
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
            server.createContext("/api/session") { exchange ->
                val body = "{\"data\":{\"sessionID\":\"ses_456\"}}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.createSession(port)

            assertIs<ApiResult.Failure>(result)
        }
    }

    @Test
    fun `createSession returns failure on non-2xx`() {
        withTestServer { server, port ->
            server.createContext("/api/session") { exchange ->
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
    fun `promptText posts text payload`() {
        withTestServer { server, port ->
            var requestBody = ""
            server.createContext("/api/session/ses_1/prompt") { exchange ->
                requestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val body = "{\"data\":{\"id\":\"msg_1\",\"type\":\"user\"}}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.promptText(port, "ses_1", "hello world")

            assertIs<ApiResult.Success<Unit>>(result)
            assertTrue(requestBody.contains("\"text\":\"hello world\""))
        }
    }

    @Test
    fun `fetchLatestTurnDiffSnapshot parses patch based diff files`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/diff") { exchange ->
                val body = """
                    {
                      "data": [
                        {
                          "file": "a.txt",
                          "patch": "Index: a.txt\n===================================================================\n--- a.txt\t\n+++ a.txt\t\n@@ -1 +1 @@\n-old\n+new\n",
                          "additions": 1,
                          "deletions": 1,
                          "status": "modified"
                        }
                      ]
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchLatestTurnDiffSnapshot(port, "ses_1")

            val success = assertIs<ApiResult.Success<SessionDiffSnapshot>>(result)
            assertEquals("ses_1", success.value.sessionId)
            assertEquals(1, success.value.files.size)
            assertEquals("a.txt", success.value.files.first().file)
            assertEquals("old\n", success.value.files.first().before)
            assertEquals("new\n", success.value.files.first().after)
        }
    }

    @Test
    fun `fetchLatestTurnDiffSnapshot returns empty snapshot for empty body`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/diff") { exchange ->
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            val client = SessionApiClient()
            val result = client.fetchLatestTurnDiffSnapshot(port, "ses_1")

            val success = assertIs<ApiResult.Success<SessionDiffSnapshot>>(result)
            assertEquals("ses_1", success.value.sessionId)
            assertEquals(0, success.value.files.size)
        }
    }

    @Test
    fun `fetchLatestTurnDiffSnapshot returns failure on malformed array payload`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/diff") { exchange ->
                val body = "{\"data\":{\"not\":\"array\"}}"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchLatestTurnDiffSnapshot(port, "ses_1")

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals("GET /api/session/ses_1/diff: Expected JSON array payload", error.message)
        }
    }

    @Test
    fun `fetchSessionDiffSnapshot fetches first to last user turn range`() {
        withTestServer { server, port ->
            val diffQueries = mutableListOf<String>()
            // v2 message lists arrive newest-first with `time.created`; the diff
            // range route requires `from` (oldest) to precede `to` (newest).
            server.createContext("/api/session/ses_1/message") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "msg_u2", "type": "user", "text": "second", "time": {"created": 300}},
                        {"id": "msg_a2", "type": "assistant", "content": [], "time": {"created": 250}},
                        {"id": "msg_u1", "type": "user", "text": "first", "time": {"created": 100}}
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            server.createContext("/api/session/ses_1/diff") { exchange ->
                diffQueries.add(exchange.requestURI.query.orEmpty())
                val body = """
                    {
                      "data": [
                        {
                          "file": "a.txt",
                          "patch": "Index: a.txt\n===================================================================\n--- /dev/null\n+++ a.txt\n@@ -0,0 +1 @@\n+new\n",
                          "additions": 1,
                          "deletions": 0,
                          "status": "added"
                        }
                      ]
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionDiffSnapshot(port, "ses_1")

            val success = assertIs<ApiResult.Success<SessionDiffSnapshot>>(result)
            assertEquals(1, success.value.files.size)
            assertEquals("a.txt", success.value.files.first().file)
            assertEquals(listOf("from=msg_u1&to=msg_u2"), diffQueries)
        }
    }

    @Test
    fun `fetchSessionDiffSnapshot orders tied user messages by arrival order, not timestamp`() {
        withTestServer { server, port ->
            val diffQueries = mutableListOf<String>()
            // Two user messages share a `time.created` timestamp. The list still
            // arrives newest-first, so the older message is the last one listed;
            // the range must follow that arrival order — a stable timestamp sort
            // would keep the newer message first and invert `from`/`to` (400
            // InvalidRequestError from the server).
            server.createContext("/api/session/ses_1/message") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "msg_u2", "type": "user", "text": "second", "time": {"created": 100}},
                        {"id": "msg_a1", "type": "assistant", "content": [], "time": {"created": 100}},
                        {"id": "msg_u1", "type": "user", "text": "first", "time": {"created": 100}}
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            server.createContext("/api/session/ses_1/diff") { exchange ->
                diffQueries.add(exchange.requestURI.query.orEmpty())
                val body = """
                    {"data": []}
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionDiffSnapshot(port, "ses_1")

            assertIs<ApiResult.Success<SessionDiffSnapshot>>(result)
            assertEquals(listOf("from=msg_u1&to=msg_u2"), diffQueries)
        }
    }

    @Test
    fun `fetchSessionDiffSnapshot falls back to latest turn when session has no user messages`() {
        withTestServer { server, port ->
            val diffQueries = mutableListOf<String?>()
            server.createContext("/api/session/ses_1/message") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "msg_a1", "type": "assistant", "content": []}
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            server.createContext("/api/session/ses_1/diff") { exchange ->
                diffQueries.add(exchange.requestURI.query)
                val body = """
                    {"data": [
                      {
                        "file": "a.txt",
                        "patch": "Index: a.txt\n===================================================================\n--- /dev/null\n+++ a.txt\n@@ -0,0 +1 @@\n+new\n",
                        "additions": 1,
                        "deletions": 0,
                        "status": "added"
                      }
                    ]}
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionDiffSnapshot(port, "ses_1")

            val success = assertIs<ApiResult.Success<SessionDiffSnapshot>>(result)
            assertEquals(1, success.value.files.size)
            assertEquals(listOf<String?>(null), diffQueries)
        }
    }

    @Test
    fun `fetchFileDiffPreview reconstructs preview from patch-only diff`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/message") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "msg_u1", "type": "user", "text": "first"}
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            server.createContext("/api/session/ses_1/diff") { exchange ->
                val body = """
                    {"data": [
                      {
                        "file": "a.txt",
                        "patch": "Index: a.txt\n===================================================================\n--- a.txt\t\n+++ a.txt\t\n@@ -1 +1 @@\n-old\n+new\n",
                        "additions": 1,
                        "deletions": 1,
                        "status": "modified"
                      }
                    ]}
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchFileDiffPreview(port, "ses_1", "/repo", "/repo/a.txt")

            val success = assertIs<ApiResult.Success<SessionApiClient.FileDiffPreview?>>(result)
            assertEquals("old\n", success.value?.before)
            assertEquals("new\n", success.value?.after)
        }
    }

    @Test
    fun `fetchFileDiffPreview returns success null when file is not in diff`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/message") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "msg_u1", "type": "user", "text": "first"}
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            server.createContext("/api/session/ses_1/diff") { exchange ->
                val body = """
                    {"data": [
                      {
                        "file": "a.txt",
                        "patch": "Index: a.txt\n===================================================================\n--- a.txt\t\n+++ a.txt\t\n@@ -1 +1 @@\n-old\n+new\n",
                        "additions": 1,
                        "deletions": 1,
                        "status": "modified"
                      }
                    ]}
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
    fun `fetchSessionHierarchy projects metadata and sends directory query`() {
        withTestServer { server, port ->
            var query: String? = null
            server.createContext("/api/session") { exchange ->
                query = exchange.requestURI.query
                val body = """
                    {
                      "data": [
                        {
                          "id": "ses_1",
                          "projectID": "prj_1",
                          "title": "Root",
                          "location": {"directory": "/repo"},
                          "time": { "created": 100, "updated": 123 }
                        },
                        {
                          "id": "ses_2",
                          "parentID": "ses_1",
                          "title": "Child"
                        }
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionHierarchy(port, directory = "/repo")

            val success = assertIs<ApiResult.Success<List<Session>>>(result)
            assertEquals(2, success.value.size)
            val ses1 = success.value.first { it.id == "ses_1" }
            val ses2 = success.value.first { it.id == "ses_2" }
            assertEquals("Root", ses1.title)
            assertEquals("/repo", ses1.directory)
            assertEquals(123L, ses1.time.updated)
            assertEquals("ses_1", ses2.parentID)
            // URI.getQuery() returns the decoded form of the query component.
            val sentQuery = checkNotNull(query)
            assertTrue(sentQuery.contains("directory=/repo"), "expected directory query, got $sentQuery")
            assertTrue(sentQuery.contains("limit=100"), "expected page size query, got $sentQuery")
        }
    }

    @Test
    fun `fetchSessionHierarchy follows cursor pagination`() {
        withTestServer { server, port ->
            val cursors = mutableListOf<String?>()
            server.createContext("/api/session") { exchange ->
                val sentQuery = exchange.requestURI.query.orEmpty()
                cursors.add(if (sentQuery.contains("cursor=")) sentQuery.substringAfter("cursor=") else null)
                val body = if (cursors.size == 1) {
                    """
                    {
                      "data": [
                        {"id": "ses_1", "title": "First page"}
                      ],
                      "cursor": {"next": "cursor_1", "previous": null}
                    }
                    """
                } else {
                    """
                    {
                      "data": [
                        {"id": "ses_2", "title": "Second page"}
                      ],
                      "cursor": {"next": null, "previous": "cursor_1"}
                    }
                    """
                }
                val trimmed = body.trimIndent()
                exchange.sendResponseHeaders(200, trimmed.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(trimmed.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionHierarchy(port)

            val success = assertIs<ApiResult.Success<List<Session>>>(result)
            assertEquals(listOf("ses_1", "ses_2"), success.value.map { it.id })
            assertEquals(listOf<String?>(null, "cursor_1"), cursors)
        }
    }

    @Test
    fun `fetchSessionHierarchy returns failure on malformed payload`() {
        withTestServer { server, port ->
            server.createContext("/api/session") { exchange ->
                val body = "[1,2,3]"
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionHierarchy(port)

            val failure = assertIs<ApiResult.Failure>(result)
            val error = assertIs<ApiError.ParseError>(failure.error)
            assertEquals("GET /api/session: Expected JSON object", error.message)
        }
    }

    @Test
    fun `fetchSessionMessageDiffSummaries maps assistant snapshot files`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1/message") { exchange ->
                val body = """
                    {
                      "data": [
                        {"id": "msg_u1", "type": "user", "text": "first"},
                        {"id": "msg_a1", "type": "assistant", "content": [], "snapshot": {"files": ["a.txt", "b.txt"]}}
                      ],
                      "cursor": {"next": null, "previous": null}
                    }
                """.trimIndent()
                exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val client = SessionApiClient()
            val result = client.fetchSessionMessageDiffSummaries(port, "ses_1")

            val success = assertIs<ApiResult.Success<List<SessionApiClient.SessionMessageDiffSummary>>>(result)
            assertEquals(1, success.value.size)
            val summary = success.value.first()
            assertEquals("msg_a1", summary.messageId)
            assertEquals("assistant", summary.role)
            assertEquals(listOf("a.txt", "b.txt"), summary.files)
        }
    }

    @Test
    fun `deleteSession parses empty 204 as success`() {
        withTestServer { server, port ->
            server.createContext("/api/session/ses_1") { exchange ->
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            val client = SessionApiClient()
            val result = client.deleteSession(port, "ses_1")

            val success = assertIs<ApiResult.Success<Boolean>>(result)
            assertEquals(true, success.value)
        }
    }

    @Test
    fun `updateSession sends title payload`() {
        withTestServer { server, port ->
            var requestBody = ""
            server.createContext("/api/session/ses_1") { exchange ->
                requestBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() }
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }

            val client = SessionApiClient()
            val result = client.updateSession(port, "ses_1", "New Title")

            assertIs<ApiResult.Success<Unit>>(result)
            assertTrue(requestBody.contains("\"title\":\"New Title\""))
        }
    }

}
