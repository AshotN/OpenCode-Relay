package com.ashotn.opencode.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the OpenCode server's SSE event stream (`GET /event`) and
 * dispatches parsed [OpenCodeEvent]s to a listener on a background thread.
 *
 * - Automatically reconnects on disconnect (with a short backoff).
 * - Stops cleanly when [stop] is called.
 * - Unknown event types are silently ignored.
 *
 * Thread safety: [start] and [stop] may be called from any thread.
 */
class SseClient(
    private val port: Int,
    private val onEvent: (OpenCodeEvent) -> Unit,
) {
    private val log = logger<SseClient>()

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "opencode-sse").apply { isDaemon = true }
    }
    private var future: Future<*>? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        future = executor.submit { runLoop() }
    }

    fun stop() {
        running.set(false)
        future?.cancel(true)
        future = null
    }

    // --- Internal ---

    private fun runLoop() {
        log.info("SseClient: runLoop started on port $port")
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                connect()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log.warn("SseClient: connection error, will retry: ${e.message}")
            }
            if (running.get()) {
                try {
                    TimeUnit.SECONDS.sleep(3)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun connect() {
        log.info("SseClient: connecting to http://localhost:$port/event")
        val url = URI("http://localhost:$port/event").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.connectTimeout = 5_000
        conn.readTimeout = 0 // keep-alive; never time out reads
        conn.connect()

        try {
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val dataBuffer = StringBuilder()

            reader.forEachLine { line ->
                if (Thread.currentThread().isInterrupted) return@forEachLine

                when {
                    line.startsWith("data:") -> {
                        dataBuffer.append(line.removePrefix("data:").trim())
                    }
                    line.isEmpty() && dataBuffer.isNotEmpty() -> {
                        // Blank line = end of event; dispatch and reset buffer
                        dispatchRaw(dataBuffer.toString())
                        dataBuffer.clear()
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun dispatchRaw(json: String) {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            val type = root.get("type")?.asString ?: return
            val properties = root.getAsJsonObject("properties") ?: JsonObject()

            val event: OpenCodeEvent? = when (type) {
                "session.diff"       -> parseSessionDiff(properties)
                "session.idle"       -> parseSessionIdle(properties)
                "session.status"     -> parseSessionStatus(properties)
                "message.part.updated" -> parseMessagePartUpdated(properties)
                "permission.asked"   -> parsePermissionAsked(properties)
                "permission.replied" -> parsePermissionReplied(properties)
                else                 -> null
            }

            if (event != null) {
                log.debug("SseClient: dispatching ${event::class.simpleName}")
                onEvent(event)
            }
        } catch (e: Exception) {
            log.warn("SseClient: failed to parse event: ${e.message}. JSON: ${json.take(500)}")
        }
    }

    /**
     * Parses a `session.diff` event.
     *
     * Shape:
     * ```json
     * {
     *   "sessionID": "ses_...",
     *   "diff": [
     *     { "file": "notes.md", "before": "", "after": "...",
     *       "additions": 27, "deletions": 0, "status": "added" }
     *   ]
     * }
     * ```
     */
    private fun parseSessionDiff(props: JsonObject): OpenCodeEvent.SessionDiff? {
        val sessionId = props.get("sessionID")?.asString ?: return null
        val diffArray = props.getAsJsonArray("diff") ?: return null
        val files = diffArray.mapNotNull { elem ->
            val obj = elem.asJsonObject
            val file      = obj.get("file")?.asString      ?: return@mapNotNull null
            val before    = obj.get("before")?.asString    ?: ""
            val after     = obj.get("after")?.asString     ?: ""
            val additions = obj.get("additions")?.asInt    ?: 0
            val deletions = obj.get("deletions")?.asInt    ?: 0
            val status    = obj.get("status")?.asString    ?: "modified"
            OpenCodeEvent.SessionDiffFile(file, before, after, additions, deletions, status)
        }
        log.debug("SseClient: session.diff sessionId=$sessionId files=${files.map { it.file }}")
        return OpenCodeEvent.SessionDiff(sessionId, files)
    }

    private fun parsePermissionReplied(props: JsonObject): OpenCodeEvent.PermissionReplied? {
        val requestId = props.get("requestID")?.asString ?: return null
        val sessionId = props.get("sessionID")?.asString ?: return null
        val reply     = props.get("reply")?.asString     ?: return null
        return OpenCodeEvent.PermissionReplied(requestId, sessionId, reply)
    }

    private fun parsePermissionAsked(props: JsonObject): OpenCodeEvent.PermissionAsked? {
        val id = props.get("id")?.asString ?: return null
        val sessionId = props.get("sessionID")?.asString ?: return null
        val permission = props.get("permission")?.asString ?: return null
        val patterns = props.getAsJsonArray("patterns")
            ?.mapNotNull { it.asString } ?: emptyList()
        val metadata = props.getAsJsonObject("metadata")
            ?.entrySet()
            ?.associate { (k, v) -> k to v.asString }
            ?: emptyMap()
        return OpenCodeEvent.PermissionAsked(id, sessionId, permission, patterns, metadata)
    }

    /**
     * session.status — we only care about the "busy" status to signal a new turn starting.
     * Shape: { "sessionID": "...", "status": { "type": "busy" | "idle" } }
     */
    private fun parseSessionStatus(props: JsonObject): OpenCodeEvent.SessionBusy? {
        val sessionId = props.get("sessionID")?.asString ?: return null
        val statusType = props.getAsJsonObject("status")?.get("type")?.asString ?: return null
        return if (statusType == "busy") OpenCodeEvent.SessionBusy(sessionId) else null
    }

    /**
     * message.part.updated — we only care about parts with type == "patch", which carry
     * the messageID needed to revert the turn via POST /session/:id/revert.
     * Shape: { "part": { "sessionID": "...", "messageID": "...", "type": "patch", ... } }
     */
    private fun parseMessagePartUpdated(props: JsonObject): OpenCodeEvent.TurnPatch? {
        val part = props.getAsJsonObject("part") ?: return null
        if (part.get("type")?.asString != "patch") return null
        val sessionId = part.get("sessionID")?.asString ?: return null
        val messageId = part.get("messageID")?.asString ?: return null
        val hash      = part.get("hash")?.asString      ?: return null
        val files = part.getAsJsonArray("files")?.mapNotNull { it.asString } ?: emptyList()
        return OpenCodeEvent.TurnPatch(sessionId, messageId, files, hash)
    }

    private fun parseSessionIdle(props: JsonObject): OpenCodeEvent.SessionIdle? {
        val sessionId = props.get("sessionID")?.asString ?: return null
        return OpenCodeEvent.SessionIdle(sessionId)
    }
}
