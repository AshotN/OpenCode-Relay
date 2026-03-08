package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import com.ashotn.opencode.util.getIntOrNull
import com.ashotn.opencode.util.getObjectOrNull
import com.ashotn.opencode.util.getStringOrNull
import com.ashotn.opencode.util.serverUrl
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI

internal class SessionApiClient {
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

    fun fetchSessionDiffSnapshot(currentPort: Int, sessionId: String): DiffSnapshotResult {
        val url = URI(serverUrl(currentPort, "/session/$sessionId/diff")).toURL()
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 8_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            if (code !in 200..299) {
                return DiffSnapshotResult(success = false, event = null)
            }

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.isBlank()) {
                return DiffSnapshotResult(success = true, event = OpenCodeEvent.SessionDiff(sessionId, emptyList()))
            }

            val root = JsonParser.parseString(body)
            val filesNode = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.get("files")?.takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: return DiffSnapshotResult(success = true, event = OpenCodeEvent.SessionDiff(sessionId, emptyList()))

            val files = filesNode.mapNotNull { element ->
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
        } finally {
            conn.disconnect()
        }
    }

    fun fetchFileDiffPreview(currentPort: Int, sessionId: String, projectBase: String, absFilePath: String): FileDiffPreviewResult {
        val snapshot = fetchSessionDiffSnapshot(currentPort, sessionId)
        if (!snapshot.success) return FileDiffPreviewResult(success = false, before = null, after = null)
        val match = snapshot.event?.files?.firstOrNull { diffFile ->
            DiffTextUtil.toAbsolutePath(projectBase, diffFile.file) == absFilePath
        } ?: return FileDiffPreviewResult(success = true, before = null, after = null)
        return FileDiffPreviewResult(success = true, before = match.before, after = match.after)
    }

    fun fetchSessionHierarchy(currentPort: Int): HierarchySnapshot {
        val url = URI(serverUrl(currentPort, "/session")).toURL()
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            if (code !in 200..299) return HierarchySnapshot(emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JsonParser.parseString(body)
            if (!root.isJsonArray) return HierarchySnapshot(emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

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
                    ?: sessionObj.getStringOrNull("name")
                if (!title.isNullOrBlank()) {
                    titleBySession[id] = title
                }

                val description = sessionObj.getStringOrNull("description")
                    ?: sessionObj.getStringOrNull("summary")
                if (!description.isNullOrBlank()) {
                    descriptionBySession[id] = description
                }

                val parent = sessionObj.getStringOrNull("parentID")
                    ?: sessionObj.getStringOrNull("parentId")
                    ?: sessionObj.getStringOrNull("parent")
                if (!parent.isNullOrBlank()) {
                    parentByChild[id] = parent
                }

                val timeObj = sessionObj.getObjectOrNull("time")
                val updatedAt = timeObj?.get("updated")?.takeIf { it.isJsonPrimitive }?.asLong
                if (updatedAt != null && updatedAt > 0L) {
                    updatedAtBySession[id] = updatedAt
                }
            }
            HierarchySnapshot(
                sessionIds = sessionIds,
                parentBySessionId = parentByChild,
                titleBySessionId = titleBySession,
                descriptionBySessionId = descriptionBySession,
                updatedAtBySessionId = updatedAtBySession,
            )
        } finally {
            conn.disconnect()
        }
    }

}
