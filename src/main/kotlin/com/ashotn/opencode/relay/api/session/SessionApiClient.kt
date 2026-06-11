package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.mapJsonArrayResponse
import com.ashotn.opencode.relay.api.transport.mapJsonObjectResponse
import com.ashotn.opencode.relay.api.transport.parseBooleanResponse
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.ashotn.opencode.relay.ipc.PatchDiffTextParser
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import com.ashotn.opencode.relay.util.getIntOrNull
import com.ashotn.opencode.relay.util.getObjectOrNull
import com.ashotn.opencode.relay.util.getStringOrNull
import com.ashotn.opencode.relay.util.pathsEqual
import com.ashotn.opencode.relay.util.toAbsolutePath
import com.google.gson.JsonArray
import com.google.gson.JsonObject

class SessionApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
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

    fun createSession(port: Int): ApiResult<CreatedSession> {
        val endpoint = SessionEndpoints.create()
        val response = transport.post(port = port, path = endpoint.path, payload = JsonObject().toString())
        return transport.mapJsonObjectResponse(response) { sessionObj ->
            val id = sessionObj.getStringOrNull("id")
            if (id.isNullOrBlank()) {
                ApiResult.Failure(ApiError.ParseError("Session create response is missing id"))
            } else {
                ApiResult.Success(CreatedSession(id))
            }
        }.withParseContext(endpoint)
    }

    fun promptTextAsync(
        port: Int,
        sessionId: String,
        providerId: String,
        modelId: String,
        text: String,
    ): ApiResult<Unit> {
        val endpoint = SessionEndpoints.promptAsync(sessionId)
        val parts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
        }
        val payload = JsonObject().apply {
            add("model", JsonObject().apply {
                addProperty("providerID", providerId)
                addProperty("modelID", modelId)
            })
            add("parts", parts)
        }
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

    fun fetchSessionDiffSnapshot(
        port: Int,
        sessionId: String,
        messageId: String? = null,
    ): ApiResult<SessionDiffSnapshot> {
        if (messageId == null) {
            return fetchSessionMessageDiffSnapshot(port, sessionId)
        }

        val endpoint = SessionEndpoints.diff(sessionId, messageId)
        return when (val response = transport.get(port = port, path = endpoint.path)) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> {
                val body = response.value
                if (body.isNullOrBlank()) {
                    ApiResult.Success(SessionDiffSnapshot(sessionId, emptyList()))
                } else {
                    transport.mapJsonArrayResponse(ApiResult.Success(body)) { diffArray ->
                        val files =
                            diffArray.mapNotNull { element -> parseSessionDiffFile(element.asJsonObjectOrNull()) }
                        ApiResult.Success(SessionDiffSnapshot(sessionId, files))
                    }.withParseContext(endpoint)
                }
            }
        }
    }

    fun fetchSessionMessageDiffSnapshot(port: Int, sessionId: String): ApiResult<SessionDiffSnapshot> {
        return when (val refsResult = fetchSessionMessageDiffRefs(port, sessionId)) {
            is ApiResult.Failure -> refsResult
            is ApiResult.Success -> {
                val messageIds = refsResult.value
                if (messageIds.isEmpty()) return ApiResult.Success(SessionDiffSnapshot(sessionId, emptyList()))

                val mergedFilesByPath = linkedMapOf<String, SessionDiffFile>()
                for (messageId in messageIds) {
                    when (val diffResult = fetchSessionDiffSnapshot(port, sessionId, messageId)) {
                        is ApiResult.Failure -> return diffResult
                        is ApiResult.Success -> mergeDiffFiles(mergedFilesByPath, diffResult.value.files)
                    }
                }
                ApiResult.Success(SessionDiffSnapshot(sessionId, mergedFilesByPath.values.toList()))
            }
        }
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

    private fun fetchSessionMessageDiffRefs(port: Int, sessionId: String): ApiResult<List<String>> {
        return when (val summariesResult = fetchSessionMessageDiffSummaries(port, sessionId)) {
            is ApiResult.Failure -> summariesResult
            is ApiResult.Success -> ApiResult.Success(
                summariesResult.value.mapNotNull { summary ->
                    summary.messageId?.takeIf { summary.role == "user" && summary.files.isNotEmpty() }
                },
            )
        }
    }

    fun fetchSessionMessageDiffSummaries(port: Int, sessionId: String): ApiResult<List<SessionMessageDiffSummary>> {
        val endpoint = SessionEndpoints.messages(sessionId)
        val response = transport.get(port = port, path = endpoint.path)
        return transport.mapJsonArrayResponse(response) { messagesArray ->
            val summaries = messagesArray.mapNotNull { element ->
                val messageObj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val info = messageObj.getObjectOrNull("info") ?: return@mapNotNull null
                parseMessageDiffSummary(info)
            }
            ApiResult.Success(summaries)
        }.withParseContext(endpoint)
    }

    private fun parseMessageDiffSummary(info: JsonObject): SessionMessageDiffSummary? {
        val diffs = info.getObjectOrNull("summary")
            ?.get("diffs")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        val files = diffs.mapNotNull { diffElement ->
            diffElement.asJsonObjectOrNull()?.getStringOrNull("file")
        }
        if (files.isEmpty()) return null
        return SessionMessageDiffSummary(
            messageId = info.getStringOrNull("id"),
            role = info.getStringOrNull("role"),
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

    private fun mergeDiffFiles(
        mergedFilesByPath: MutableMap<String, SessionDiffFile>,
        files: List<SessionDiffFile>,
    ) {
        for (file in files) {
            val existing = mergedFilesByPath[file.file]
            if (existing == null) {
                mergedFilesByPath[file.file] = file
                continue
            }
            mergedFilesByPath[file.file] = existing.copy(
                after = file.after,
                additions = existing.additions + file.additions,
                deletions = existing.deletions + file.deletions,
                status = mergeStatus(existing.status, file.status),
            )
        }
    }

    // Build one final status from multiple message diffs for the same file.
    // Example: ADDED in turn 1 + MODIFIED in turn 2 should still show as ADDED.
    private fun mergeStatus(existing: SessionDiffStatus, next: SessionDiffStatus): SessionDiffStatus = when {
        next == SessionDiffStatus.UNKNOWN -> existing
        existing == SessionDiffStatus.ADDED && next != SessionDiffStatus.DELETED -> SessionDiffStatus.ADDED
        else -> next
    }

    private fun com.google.gson.JsonElement?.asJsonObjectOrNull(): JsonObject? =
        this?.takeIf { it.isJsonObject }?.asJsonObject

    fun fetchSessionHierarchy(port: Int): ApiResult<List<Session>> {
        val endpoint = SessionEndpoints.list()
        val response = transport.get(port = port, path = endpoint.path)
        return transport.mapJsonArrayResponse(response) { sessionArray ->
            val sessions = mutableListOf<Session>()
            for (element in sessionArray) {
                if (!element.isJsonObject) continue
                when (val result = parseSession(element.asJsonObject)) {
                    is ApiResult.Failure -> return@mapJsonArrayResponse result
                    is ApiResult.Success -> sessions.add(result.value)
                }
            }
            ApiResult.Success(sessions)
        }.withParseContext(endpoint)
    }

    private fun parseSession(obj: JsonObject): ApiResult<Session> {
        val id = obj.getStringOrNull("id")
        if (id.isNullOrBlank()) {
            return ApiResult.Failure(ApiError.ParseError("Session is missing id"))
        }

        val projectID = obj.getStringOrNull("projectID")
        val directory = obj.getStringOrNull("directory")
        val parentID = obj.getStringOrNull("parentID")
        val title = obj.getStringOrNull("title")
        val version = obj.getStringOrNull("version")

        val timeObj = obj.getObjectOrNull("time")
        val timeCreated = timeObj?.get("created")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
        val timeUpdated = timeObj?.get("updated")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
        val timeCompacting = timeObj?.get("compacting")
            ?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() }
        val sessionTime = SessionTime(created = timeCreated, updated = timeUpdated, compacting = timeCompacting)

        val summaryObj = obj.getObjectOrNull("summary")
        val summary = if (summaryObj != null) {
            val additions = summaryObj.getIntOrNull("additions") ?: 0
            val deletions = summaryObj.getIntOrNull("deletions") ?: 0
            val files = summaryObj.getIntOrNull("files") ?: 0
            val diffsArray = summaryObj.get("diffs")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
            val diffs = diffsArray?.mapNotNull { diffElement ->
                if (!diffElement.isJsonObject) return@mapNotNull null
                val diffObj = diffElement.asJsonObject
                val file = diffObj.getStringOrNull("file") ?: return@mapNotNull null
                val diffText = PatchDiffTextParser.parse(diffObj)
                FileDiff(
                    file = file,
                    before = diffText.before,
                    after = diffText.after,
                    additions = diffObj.getIntOrNull("additions") ?: 0,
                    deletions = diffObj.getIntOrNull("deletions") ?: 0,
                )
            }
            SessionSummary(additions = additions, deletions = deletions, files = files, diffs = diffs)
        } else {
            null
        }

        val shareObj = obj.getObjectOrNull("share")
        val share = if (shareObj != null) {
            SessionShare(url = shareObj.getStringOrNull("url"))
        } else {
            null
        }

        return ApiResult.Success(
            Session(
                id = id,
                projectID = projectID,
                directory = directory,
                parentID = parentID,
                title = title,
                version = version,
                time = sessionTime,
                summary = summary,
                share = share,
            )
        )
    }

    fun deleteSession(port: Int, sessionId: String): ApiResult<Boolean> {
        val endpoint = SessionEndpoints.delete(sessionId)
        val response = transport.delete(port = port, path = endpoint.path)
        return transport.parseBooleanResponse(response).withParseContext(endpoint)
    }

    fun updateSession(port: Int, sessionId: String, title: String): ApiResult<Session> {
        val endpoint = SessionEndpoints.update(sessionId)
        val payload = JsonObject().apply {
            addProperty("title", title)
        }
        val response = transport.patch(port = port, path = endpoint.path, payload = payload.toString())
        return transport.mapJsonObjectResponse(response) { parseSession(it) }
            .withParseContext(endpoint)
    }

}
