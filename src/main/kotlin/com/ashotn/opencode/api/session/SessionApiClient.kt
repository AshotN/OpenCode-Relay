package com.ashotn.opencode.api.session

import com.ashotn.opencode.api.transport.ApiError
import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.transport.OpenCodeHttpTransport
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

    data class DiffSnapshotResult(
        val success: Boolean,
        val event: OpenCodeEvent.SessionDiff?,
    )

    data class FileDiffPreviewResult(
        val success: Boolean,
        val before: String?,
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
        val response = transport.postJson(port = port, path = "/session", payload = JsonObject().toString())
        return when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> {
                val objectResult = transport.parseJsonObject(response.value)
                when (objectResult) {
                    is ApiResult.Failure -> objectResult
                    is ApiResult.Success -> {
                        val id = objectResult.value.getStringOrNull("id")
                        if (id.isNullOrBlank()) {
                            ApiResult.Failure(ApiError.ParseError("Session create response is missing id"))
                        } else {
                            ApiResult.Success(CreatedSession(id))
                        }
                    }
                }
            }
        }
    }

    fun fetchSessionDiffSnapshot(port: Int, sessionId: String): DiffSnapshotResult {
        val response = transport.get(port = port, path = "/session/$sessionId/diff")
        return when (response) {
            is ApiResult.Failure -> DiffSnapshotResult(success = false, event = null)
            is ApiResult.Success -> {
                val body = response.value
                if (body.isNullOrBlank()) {
                    return DiffSnapshotResult(success = true, event = OpenCodeEvent.SessionDiff(sessionId, emptyList()))
                }

                val rootResult = transport.parseJsonElement(body)
                when (rootResult) {
                    is ApiResult.Failure -> DiffSnapshotResult(success = false, event = null)
                    is ApiResult.Success -> {
                        val root = rootResult.value
                        if (!root.isJsonArray) {
                            return DiffSnapshotResult(success = false, event = null)
                        }

                        val files = root.asJsonArray.mapNotNull { element ->
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

                        DiffSnapshotResult(success = true, event = OpenCodeEvent.SessionDiff(sessionId, files))
                    }
                }
            }
        }
    }

    fun fetchFileDiffPreview(port: Int, sessionId: String, projectBase: String, absFilePath: String): FileDiffPreviewResult {
        val snapshot = fetchSessionDiffSnapshot(port, sessionId)
        if (!snapshot.success) return FileDiffPreviewResult(success = false, before = null, after = null)

        val match = snapshot.event?.files?.firstOrNull { diffFile ->
            toAbsolutePath(projectBase, diffFile.file) == absFilePath
        } ?: return FileDiffPreviewResult(success = true, before = null, after = null)

        return FileDiffPreviewResult(success = true, before = match.before, after = match.after)
    }

    fun fetchSessionHierarchy(port: Int): ApiResult<HierarchySnapshot> {
        val response = transport.get(port = port, path = "/session")
        return when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> {
                val rootResult = transport.parseJsonElement(response.value)
                when (rootResult) {
                    is ApiResult.Failure -> rootResult
                    is ApiResult.Success -> {
                        val root = rootResult.value
                        if (!root.isJsonArray) {
                            return ApiResult.Failure(ApiError.ParseError("Expected session list array"))
                        }

                        val sessionIds = linkedSetOf<String>()
                        val parentByChild = HashMap<String, String>()
                        val titleBySession = HashMap<String, String>()
                        val descriptionBySession = HashMap<String, String>()
                        val updatedAtBySession = HashMap<String, Long>()

                        root.asJsonArray.forEach { element ->
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
                    }
                }
            }
        }
    }

    private fun toAbsolutePath(projectBase: String, path: String): String {
        val normalized = path.replace('\\', '/')
        return if (normalized.startsWith('/')) normalized else "$projectBase/$normalized"
    }
}
