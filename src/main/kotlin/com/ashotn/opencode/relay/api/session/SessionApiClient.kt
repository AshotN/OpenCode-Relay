package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.api.transport.ApiEndpoint
import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.flatMap
import com.ashotn.opencode.relay.api.transport.map
import com.ashotn.opencode.relay.api.transport.mapJsonObjectResponse
import com.ashotn.opencode.relay.api.transport.parseBooleanResponse
import com.ashotn.opencode.relay.api.transport.parseJsonArrayResponse
import com.ashotn.opencode.relay.api.transport.parseJsonEnvelope
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.ashotn.opencode.relay.ipc.PatchDiffTextParser
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import com.ashotn.opencode.relay.util.getIntOrNull
import com.ashotn.opencode.relay.util.getObjectOrNull
import com.ashotn.opencode.relay.util.getStringOrNull
import com.ashotn.opencode.relay.util.pathsEqual
import com.ashotn.opencode.relay.util.toAbsolutePath
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.logger
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SessionApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    companion object {
        private val log = logger<SessionApiClient>()

        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 50
    }

    data class CreatedSession(
        val sessionId: String,
    )

    data class FileDiffPreview(
        val before: String,
        val after: String,
    )

    data class SessionMessageDiffSummary(
        val messageId: String?,
        val role: String?,
        val files: List<String>,
    )

    /**
     * A single session-scoped permission rule (v2 `Permission.Rule`).
     * `effect` is one of `"allow"`, `"deny"`, `"ask"`.
     */
    data class PermissionRule(
        val action: String,
        val resource: String,
        val effect: String,
    )

    /**
     * A model reference for session creation (v2 `Model.Ref`).
     */
    data class SessionModelRef(
        val providerID: String,
        val modelID: String,
    )

    /**
     * Creates a session, optionally with explicit session-scoped permission rules
     * and a pinned model.
     *
     * OpenCode 2.x removed the v1 `OPENCODE_PERMISSION` environment variable;
     * session permissions are provided here instead of via server config.
     */
    fun createSession(
        port: Int,
        permissions: List<PermissionRule>? = null,
        model: SessionModelRef? = null,
    ): ApiResult<CreatedSession> {
        val endpoint = SessionEndpoints.create()
        val payload = JsonObject().also { body ->
            permissions.orEmpty().takeIf { it.isNotEmpty() }?.let { rules ->
                val rulesArray = JsonArray()
                rules.forEach { rule ->
                    rulesArray.add(
                        JsonObject().also {
                            it.addProperty("action", rule.action)
                            it.addProperty("resource", rule.resource)
                            it.addProperty("effect", rule.effect)
                        },
                    )
                }
                body.add("permissions", rulesArray)
            }
            model?.let { ref ->
                body.add(
                    "model",
                    JsonObject().also {
                        it.addProperty("providerID", ref.providerID)
                        it.addProperty("id", ref.modelID)
                    },
                )
            }
        }
        val response = transport.post(port = port, path = endpoint.path, payload = payload.toString())
        return transport.mapJsonObjectResponse(response) { sessionObj ->
            val id = sessionObj.getStringOrNull("id")
            if (id.isNullOrBlank()) {
                ApiResult.Failure(ApiError.ParseError("Session create response is missing id"))
            } else {
                ApiResult.Success(CreatedSession(id))
            }
        }.withParseContext(endpoint)
    }

    /**
     * Enqueues a user prompt on the session's durable inbox and starts its execution.
     * Replaces the v1 `prompt_async` route (v2 `POST /api/session/{id}/prompt`).
     */
    fun promptText(port: Int, sessionId: String, text: String): ApiResult<Unit> {
        val endpoint = SessionEndpoints.prompt(sessionId)
        val payload = JsonObject().also { it.addProperty("text", text) }
        val response = transport.post(
            port = port,
            path = endpoint.path,
            payload = payload.toString(),
        )
        return when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> ApiResult.Success(Unit)
        }.withParseContext(endpoint)
    }

    /**
     * Snapshot of the newest user turn's file changes.
     *
     * In OpenCode 2.x the per-message diff route is gone; `GET /api/session/{id}/diff`
     * with no query returns the turn of the newest user message, which is exactly the
     * "live" snapshot the inline diff rendering needs.
     */
    fun fetchLatestTurnDiffSnapshot(
        port: Int,
        sessionId: String,
    ): ApiResult<SessionDiffSnapshot> {
        val endpoint = SessionEndpoints.diffLatestTurn(sessionId)
        return fetchDiffFiles(port, endpoint)
            .map { files -> SessionDiffSnapshot(sessionId, files) }
            .withParseContext(endpoint)
    }

    /**
     * Cumulative snapshot across all user turns of the session.
     *
     * v2 diffs are per-turn: `from`/`to` select a range of user-message turns, so the
     * cumulative view is a single range request from the first to the last user
     * message. Sessions without user messages fall back to the latest-turn snapshot.
     */
    fun fetchSessionDiffSnapshot(port: Int, sessionId: String): ApiResult<SessionDiffSnapshot> {
        val endpoint = when (val userMessageIds = fetchUserMessageIds(port, sessionId)) {
            is ApiResult.Failure -> return userMessageIds
            is ApiResult.Success -> when {
                userMessageIds.value.isEmpty() -> SessionEndpoints.diffLatestTurn(sessionId)
                userMessageIds.value.size == 1 -> SessionEndpoints.diffTurn(sessionId, userMessageIds.value.first())
                else -> SessionEndpoints.diffRange(
                    sessionId = sessionId,
                    fromUserMessageId = userMessageIds.value.first(),
                    toUserMessageId = userMessageIds.value.last(),
                )
            }
        }
        return fetchDiffFiles(port, endpoint)
            .map { files -> SessionDiffSnapshot(sessionId, files) }
            .withParseContext(endpoint)
    }

    fun fetchFileDiffPreview(
        port: Int,
        sessionId: String,
        projectBase: String,
        absFilePath: String
    ): ApiResult<FileDiffPreview?> {
        return when (val snapshot = fetchSessionDiffSnapshot(port, sessionId)) {
            is ApiResult.Failure -> snapshot
            is ApiResult.Success -> {
                val match = snapshot.value.files.firstOrNull { diffFile ->
                    pathsEqual(projectBase, toAbsolutePath(projectBase, diffFile.file), absFilePath)
                } ?: return ApiResult.Success(null)

                ApiResult.Success(FileDiffPreview(before = match.before, after = match.after))
            }
        }
    }

    /**
     * Per-message summaries used to estimate how many files a session touched.
     *
     * v2 messages are flat objects; file changes are reported on assistant messages
     * via `snapshot.files`.
     */
    fun fetchSessionMessageDiffSummaries(port: Int, sessionId: String): ApiResult<List<SessionMessageDiffSummary>> {
        val endpoint = SessionEndpoints.messages(sessionId)
        return walkPages(port, endpoint.path) { cursor -> pageQuery(cursor = cursor) }
            .flatMap { messages ->
                val summaries = messages.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    parseMessageDiffSummary(element.asJsonObject)
                }
                ApiResult.Success(summaries)
            }
            .withParseContext(endpoint)
    }

    fun fetchSessionHierarchy(port: Int, directory: String? = null): ApiResult<List<Session>> {
        val endpoint = SessionEndpoints.list()
        return walkPages(port, endpoint.path) { cursor -> pageQuery(directory = directory, cursor = cursor) }
            .flatMap { sessionElements ->
                val sessions = mutableListOf<Session>()
                for (element in sessionElements) {
                    if (!element.isJsonObject) continue
                    when (val result = parseSession(element.asJsonObject)) {
                        is ApiResult.Failure -> return@flatMap result
                        is ApiResult.Success -> sessions.add(result.value)
                    }
                }
                ApiResult.Success(sessions)
            }
            .withParseContext(endpoint)
    }

    fun deleteSession(port: Int, sessionId: String): ApiResult<Boolean> {
        val endpoint = SessionEndpoints.delete(sessionId)
        val response = transport.delete(port = port, path = endpoint.path)
        return transport.parseBooleanResponse(response, emptyBodyValue = true)
            .withParseContext(endpoint)
    }

    fun updateSession(port: Int, sessionId: String, title: String): ApiResult<Unit> {
        val endpoint = SessionEndpoints.update(sessionId)
        val payload = JsonObject().also { it.addProperty("title", title) }
        val response = transport.patch(port = port, path = endpoint.path, payload = payload.toString())
        return when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> ApiResult.Success(Unit)
        }.withParseContext(endpoint)
    }

    private fun fetchDiffFiles(port: Int, endpoint: ApiEndpoint): ApiResult<List<SessionDiffFile>> {
        val response = transport.get(port = port, path = endpoint.path)
        // Some server versions answer empty for diff routes; treat that as an empty
        // snapshot rather than a parse failure.
        val arrayResult: ApiResult<JsonArray> =
            if (response is ApiResult.Success && response.value.isNullOrBlank()) {
                ApiResult.Success(JsonArray())
            } else {
                transport.parseJsonArrayResponse(response)
            }
        // Parse context is applied by the callers, which own the endpoint.
        return arrayResult.flatMap { diffArray ->
            val files = diffArray.mapNotNull { element -> parseSessionDiffFile(element.asJsonObjectOrNull()) }
            ApiResult.Success(files)
        }
    }

    /**
     * User message IDs of the session in chronological order (oldest first).
     *
     * The v2 message list arrives newest-first, but the diff range route requires
     * `from` to precede `to`, so IDs are sorted by `time.created` ascending.
     */
    private fun fetchUserMessageIds(port: Int, sessionId: String): ApiResult<List<String>> {
        val endpoint = SessionEndpoints.messages(sessionId)
        return walkPages(port, endpoint.path) { cursor -> pageQuery(cursor = cursor) }
            .flatMap { messages ->
                val ids = messages.mapNotNull { element ->
                    if (!element.isJsonObject) return@mapNotNull null
                    val message = element.asJsonObject
                    if (message.getStringOrNull("type") != "user") return@mapNotNull null
                    val id = message.getStringOrNull("id")?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val created = message.getAsJsonObject("time")
                        ?.get("created")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asLong
                        ?: Long.MIN_VALUE
                    created to id
                }.sortedBy { it.first }
                ApiResult.Success(ids.map { it.second })
            }
            .withParseContext(endpoint)
    }

    private data class Page(
        val items: List<JsonElement>,
        val nextCursor: String?,
    )

    /**
     * v2 lists are cursor-paginated (`{data: [...], cursor: {next, previous}}`) with a
     * small default page size, so every list fetch walks pages until the cursor is gone.
     */
    private fun walkPages(port: Int, basePath: String, pageQuery: (String?) -> String): ApiResult<List<JsonElement>> {
        val allItems = mutableListOf<JsonElement>()
        var cursor: String? = null
        var pagesFetched = 0

        while (true) {
            if (pagesFetched >= MAX_PAGES) {
                log.debug("SessionApiClient: stopping pagination at $MAX_PAGES pages for $basePath")
                break
            }
            val page = when (val pageResult = fetchPage(port, basePath + pageQuery(cursor))) {
                is ApiResult.Failure -> return pageResult
                is ApiResult.Success -> pageResult.value
            }
            allItems += page.items
            pagesFetched++
            cursor = page.nextCursor
            if (cursor.isNullOrBlank()) break
        }

        return ApiResult.Success(allItems)
    }

    private fun fetchPage(port: Int, path: String): ApiResult<Page> {
        val response = transport.get(port = port, path = path)
        return when (val rootResult = transport.parseJsonEnvelope(response)) {
            is ApiResult.Failure -> rootResult
            is ApiResult.Success -> {
                val root = rootResult.value
                val items = root.get("data")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.toList()
                    .orEmpty()
                val nextCursor = root.get("cursor")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.getStringOrNull("next")
                ApiResult.Success(Page(items = items, nextCursor = nextCursor))
            }
        }
    }

    private fun pageQuery(directory: String? = null, limit: Int = PAGE_SIZE, cursor: String? = null): String {
        val params = mutableListOf<String>()
        directory?.takeIf { it.isNotBlank() }
            ?.let { params.add("directory=${URLEncoder.encode(it, StandardCharsets.UTF_8)}") }
        params.add("limit=$limit")
        cursor?.takeIf { it.isNotBlank() }
            ?.let { params.add("cursor=${URLEncoder.encode(it, StandardCharsets.UTF_8)}") }
        return "?${params.joinToString("&")}"
    }

    private fun parseMessageDiffSummary(message: JsonObject): SessionMessageDiffSummary? {
        if (message.getStringOrNull("type") != "assistant") return null
        val files = message.getObjectOrNull("snapshot")
            ?.get("files")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { element ->
                if (!element.isJsonPrimitive) return@mapNotNull null
                element.asString.takeIf { it.isNotBlank() }
            }
            ?: return null
        if (files.isEmpty()) return null
        return SessionMessageDiffSummary(
            messageId = message.getStringOrNull("id"),
            role = "assistant",
            files = files,
        )
    }

    private fun parseSessionDiffFile(obj: JsonObject?): SessionDiffFile? {
        if (obj == null) return null
        val file = obj.getStringOrNull("file") ?: return null
        val diffText = PatchDiffTextParser.parse(obj)
        val additions = obj.getIntOrNull("additions") ?: 0
        val deletions = obj.getIntOrNull("deletions") ?: 0
        val status = SessionDiffStatus.fromWire(obj.getStringOrNull("status") ?: "unknown")

        return SessionDiffFile(
            file = file,
            before = diffText.before,
            after = diffText.after,
            additions = additions,
            deletions = deletions,
            status = status,
        )
    }

    private fun parseSession(obj: JsonObject): ApiResult<Session> {
        val id = obj.getStringOrNull("id")
        if (id.isNullOrBlank()) {
            return ApiResult.Failure(ApiError.ParseError("Session is missing id"))
        }

        val projectID = obj.getStringOrNull("projectID")
        val parentID = obj.getStringOrNull("parentID")
        val title = obj.getStringOrNull("title")
        // v2 reports the session location as {directory, workspaceID?}; v1 had a flat
        // `directory` field.
        val directory = obj.getObjectOrNull("location")?.getStringOrNull("directory")

        val timeObj = obj.getObjectOrNull("time")
        val timeCreated = timeObj?.get("created")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
        val timeUpdated = timeObj?.get("updated")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
        val sessionTime = SessionTime(created = timeCreated, updated = timeUpdated, compacting = null)

        return ApiResult.Success(
            Session(
                id = id,
                projectID = projectID,
                directory = directory,
                parentID = parentID,
                title = title,
                time = sessionTime,
            ),
        )
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null
}
