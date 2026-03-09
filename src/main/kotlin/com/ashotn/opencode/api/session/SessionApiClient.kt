package com.ashotn.opencode.api.session

import com.ashotn.opencode.api.transport.ApiError
import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.api.transport.mapJsonArrayResponse
import com.ashotn.opencode.api.transport.mapJsonObjectResponse
import com.ashotn.opencode.api.transport.withParseContext
import com.ashotn.opencode.diff.DiffTextUtil
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import com.ashotn.opencode.util.getIntOrNull
import com.ashotn.opencode.util.getObjectOrNull
import com.ashotn.opencode.util.getStringOrNull
import com.google.gson.JsonObject

class SessionApiClient(
    private val transport: OpenCodeHttpTransport = OpenCodeHttpTransport(),
) {
    data class CreatedSession(
        val sessionId: String,
    )

    data class FileDiffPreview(
        val before: String,
        val after: String?,
    )

    data class HierarchySnapshot(
        val sessionIds: Set<String>,
        val parentBySessionId: Map<String, String>,
        val titleBySessionId: Map<String, String>,
        val descriptionBySessionId: Map<String, String>,
        val updatedAtBySessionId: Map<String, Long>,
    )

    fun createSession(port: Int): ApiResult<CreatedSession> {
        val endpoint = SessionEndpoints.create()
        val response = transport.postJson(port = port, path = endpoint.path, payload = JsonObject().toString())
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

    fun fetchFileDiffPreview(port: Int, sessionId: String, projectBase: String, absFilePath: String): ApiResult<FileDiffPreview?> {
        return when (val snapshot = fetchSessionDiffSnapshot(port, sessionId)) {
            is ApiResult.Failure -> snapshot
            is ApiResult.Success -> {
                val match = snapshot.value.files.firstOrNull { diffFile ->
                    DiffTextUtil.toAbsolutePath(projectBase, diffFile.file) == absFilePath
                } ?: return ApiResult.Success(null)

                ApiResult.Success(FileDiffPreview(before = match.before, after = match.after))
            }
        }
    }

    fun fetchSessionHierarchy(port: Int): ApiResult<HierarchySnapshot> {
        val endpoint = SessionEndpoints.list()
        val response = transport.get(port = port, path = endpoint.path)
        return transport.mapJsonArrayResponse(response) { sessionArray ->
            val sessionIds = linkedSetOf<String>()
            val parentByChild = HashMap<String, String>()
            val titleBySession = HashMap<String, String>()
            val descriptionBySession = HashMap<String, String>()
            val updatedAtBySession = HashMap<String, Long>()

            sessionArray.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val sessionObj = element.asJsonObject
                val id = sessionObj.getStringOrNull("id")
                    ?: sessionObj.getStringOrNull("sessionID")
                    ?: return@forEach
                sessionIds.add(id)

                val title = sessionObj.getStringOrNull("title")
                if (!title.isNullOrBlank()) {
                    titleBySession[id] = title
                }

                val description = sessionObj.getStringOrNull("description")
                if (!description.isNullOrBlank()) {
                    descriptionBySession[id] = description
                }

                val parent = sessionObj.getStringOrNull("parentID")
                if (!parent.isNullOrBlank()) {
                    parentByChild[id] = parent
                }

                val timeObj = sessionObj.getObjectOrNull("time")
                val updatedAt = timeObj?.get("updated")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asLong }.getOrNull() }
                if (updatedAt != null && updatedAt > 0L) {
                    updatedAtBySession[id] = updatedAt
                }
            }

            ApiResult.Success(
                HierarchySnapshot(
                    sessionIds = sessionIds,
                    parentBySessionId = parentByChild,
                    titleBySessionId = titleBySession,
                    descriptionBySessionId = descriptionBySession,
                    updatedAtBySessionId = updatedAtBySession,
                )
            )
        }.withParseContext(endpoint)
    }

}
