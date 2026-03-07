package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
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
    )

    fun fetchSessionDiffSnapshot(currentPort: Int, sessionId: String): DiffSnapshotResult {
        val url = URI("http://localhost:$currentPort/session/$sessionId/diff").toURL()
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
                val additions = obj.getIntOrZero("additions")
                val deletions = obj.getIntOrZero("deletions")
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
        val url = URI("http://localhost:$currentPort/session").toURL()
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 2_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")

            val code = conn.responseCode
            if (code !in 200..299) return HierarchySnapshot(emptySet(), emptyMap(), emptyMap(), emptyMap())

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JsonParser.parseString(body)
            if (!root.isJsonArray) return HierarchySnapshot(emptySet(), emptyMap(), emptyMap(), emptyMap())

            val sessionIds = linkedSetOf<String>()
            val parentByChild = HashMap<String, String>()
            val titleBySession = HashMap<String, String>()
            val descriptionBySession = HashMap<String, String>()
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
            }
            HierarchySnapshot(
                sessionIds = sessionIds,
                parentBySessionId = parentByChild,
                titleBySessionId = titleBySession,
                descriptionBySessionId = descriptionBySession,
            )
        } finally {
            conn.disconnect()
        }
    }

    data class AppendPromptResult(val success: Boolean, val statusCode: Int)

    fun appendPrompt(currentPort: Int, text: String): AppendPromptResult {
        val url = URI("http://localhost:$currentPort/tui/append-prompt").toURL()
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true

            val jsonBody = JsonObject().also { it.addProperty("text", text) }.toString()
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            AppendPromptResult(success = code in 200..299, statusCode = code)
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun JsonObject.getIntOrZero(key: String): Int {
        val element = get(key) ?: return 0
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) {
            element.asInt
        } else {
            0
        }
    }
}
