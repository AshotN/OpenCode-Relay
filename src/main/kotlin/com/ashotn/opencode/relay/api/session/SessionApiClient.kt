package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.parseBooleanResponse
import com.ashotn.opencode.relay.api.transport.mapJsonArrayResponse
import com.ashotn.opencode.relay.api.transport.mapJsonObjectResponse
import com.ashotn.opencode.relay.api.transport.withParseContext
import com.ashotn.opencode.relay.util.toAbsolutePath
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import com.ashotn.opencode.relay.util.getIntOrNull
import com.ashotn.opencode.relay.util.getObjectOrNull
import com.ashotn.opencode.relay.util.getStringOrNull
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

    fun fetchSessionDiffSnapshot(port: Int, sessionId: String): ApiResult<OpenCodeEvent.SessionDiff> {
        val endpoint = SessionEndpoints.diff(sessionId)
        return when (val response = transport.get(port = port, path = endpoint.path)) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> {
                val body = response.value
                if (body.isNullOrBlank()) {
                    ApiResult.Success(OpenCodeEvent.SessionDiff(sessionId, emptyList()))
                } else {
                    transport.mapJsonArrayResponse(ApiResult.Success(body)) { diffArray ->
                        val files = diffArray.mapNotNull { element ->
                            if (!element.isJsonObject) return@mapNotNull null
                            val obj = element.asJsonObject

                            val file = obj.getStringOrNull("file") ?: return@mapNotNull null
                            val before = obj.getStringOrNull("before") ?: ""
                            val after = obj.getStringOrNull("after") ?: ""
                            val additions = obj.getIntOrNull("additions") ?: 0
                            val deletions = obj.getIntOrNull("deletions") ?: 0
                            val status = SessionDiffStatus.fromWire(obj.getStringOrNull("status") ?: "unknown")

                            OpenCodeEvent.SessionDiffFile(
                                file = file,
                                before = before,
                                after = after,
                                additions = additions,
                                deletions = deletions,
                                status = status,
                            )
                        }
                        ApiResult.Success(OpenCodeEvent.SessionDiff(sessionId, files))
                    }.withParseContext(endpoint)
                }
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
                    toAbsolutePath(projectBase, diffFile.file) == absFilePath
                } ?: return ApiResult.Success(null)

                ApiResult.Success(FileDiffPreview(before = match.before, after = match.after))
            }
        }
    }

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
                FileDiff(
                    file = file,
                    before = diffObj.getStringOrNull("before") ?: "",
                    after = diffObj.getStringOrNull("after") ?: "",
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
