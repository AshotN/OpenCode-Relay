package com.ashotn.opencode.ipc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the OpenCode server's SSE event stream (`GET /event`) and
 * dispatches parsed [OpenCodeEvent]s to a listener on a background thread.
 *
 * - Automatically reconnects on disconnect (with bounded backoff).
 * - Stops cleanly when [stop] is called.
 *
 * Thread safety: [start] and [stop] may be called from any thread.
 */
class SseClient(
    private val port: Int,
    private val onEvent: (OpenCodeEvent) -> Unit,
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 0
        private const val RETRY_INITIAL_MS = 1_000L
        private const val RETRY_MAX_MS = 10_000L
    }

    private val log = logger<SseClient>()

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "opencode-sse").apply { isDaemon = true }
    }
    private val unknownEventTypes = Collections.synchronizedSet(mutableSetOf<String>())

    private var future: Future<*>? = null
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun start() {
        if (executor.isShutdown) return
        if (!running.compareAndSet(false, true)) return
        future = executor.submit { runLoop() }
    }

    fun stop() {
        running.set(false)
        activeConnection?.disconnect()
        activeConnection = null
        future?.cancel(true)
        future = null
        executor.shutdownNow()
    }

    private fun runLoop() {
        log.info("SseClient: runLoop started on port $port")
        var retryDelayMs = RETRY_INITIAL_MS
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                connect()
                retryDelayMs = RETRY_INITIAL_MS
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log.warn("SseClient: connection error, will retry", e)
            }

            if (running.get()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
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
        activeConnection = conn

        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.connect()

        try {
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                val dataBuffer = StringBuilder()
                reader.forEachLine { line ->
                    if (Thread.currentThread().isInterrupted) return@forEachLine

                    when {
                        line.startsWith("data:") -> dataBuffer.append(line.removePrefix("data:").trim())
                        line.isEmpty() && dataBuffer.isNotEmpty() -> {
                            dispatchRaw(dataBuffer.toString())
                            dataBuffer.clear()
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
            activeConnection = null
        }
    }

    private fun dispatchRaw(json: String) {
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) return

            val root = rootElement.asJsonObject
            val type = root.getStringOrNull("type") ?: return
            val properties = root.getObjectOrNull("properties") ?: JsonObject()

            val event: OpenCodeEvent? = when (type) {
                "session.diff" -> parseSessionDiff(properties)
                "session.idle" -> parseSessionIdle(properties)
                "session.status" -> parseSessionStatus(properties)
                "message.part.updated" -> parseMessagePartUpdated(properties)
                "permission.asked" -> parsePermissionAsked(properties)
                "permission.replied" -> parsePermissionReplied(properties)
                else -> {
                    if (unknownEventTypes.add(type)) {
                        log.warn("SseClient: unknown event type ignored: $type")
                    }
                    null
                }
            }

            if (event != null) {
                onEvent(event)
            }
        } catch (e: Exception) {
            log.warn("SseClient: failed to parse event jsonLength=${json.length}", e)
        }
    }

    private fun parseSessionDiff(props: JsonObject): OpenCodeEvent.SessionDiff? {
        val sessionId = props.getStringOrNull("sessionID")
        if (sessionId == null) {
            log.debug("SseClient: skip session.diff reason=missingSessionId")
            return null
        }
        val diffArray = props.getAsJsonArray("diff")
        if (diffArray == null) {
            log.debug("SseClient: skip session.diff reason=missingDiffArray session=$sessionId")
            return null
        }
        val files = diffArray.mapNotNull { elem ->
            if (!elem.isJsonObject) return@mapNotNull null
            val obj = elem.asJsonObject

            val file = obj.getStringOrNull("file") ?: return@mapNotNull null
            val before = obj.getStringOrNull("before") ?: ""
            val after = obj.getStringOrNull("after") ?: ""
            val additions = obj.getIntOrNull("additions") ?: 0
            val deletions = obj.getIntOrNull("deletions") ?: 0
            val statusRaw = obj.getStringOrNull("status") ?: "modified"
            val status = SessionDiffStatus.fromWire(statusRaw)
            if (status == SessionDiffStatus.UNKNOWN) {
                log.warn("SseClient: unknown session.diff status '$statusRaw' for file=$file")
            }

            OpenCodeEvent.SessionDiffFile(file, before, after, additions, deletions, status)
        }

        log.debug("SseClient: parsed session.diff session=$sessionId fileCount=${files.size}")
        return OpenCodeEvent.SessionDiff(sessionId, files)
    }

    private fun parsePermissionReplied(props: JsonObject): OpenCodeEvent.PermissionReplied? {
        val requestId = props.getStringOrNull("requestID") ?: return null
        val sessionId = props.getStringOrNull("sessionID") ?: return null
        val replyRaw = props.getStringOrNull("reply") ?: return null
        val reply = PermissionReply.fromWire(replyRaw)
        if (reply == null) {
            log.warn("SseClient: unknown permission reply '$replyRaw' for requestID=$requestId")
            return null
        }
        return OpenCodeEvent.PermissionReplied(requestId, sessionId, reply)
    }

    private fun parsePermissionAsked(props: JsonObject): OpenCodeEvent.PermissionAsked? {
        val id = props.getStringOrNull("id") ?: return null
        val sessionId = props.getStringOrNull("sessionID") ?: return null
        val permission = props.getStringOrNull("permission") ?: return null

        val patterns = props.getAsJsonArray("patterns")
            ?.mapNotNull { element ->
                if (!element.isJsonPrimitive) return@mapNotNull null
                element.asString
            }
            ?: emptyList()

        val metadata = mutableMapOf<String, String>()
        props.getAsJsonObject("metadata")
            ?.entrySet()
            ?.forEach { (key, value) ->
                if (!value.isJsonPrimitive) return@forEach
                val primitive = value.asJsonPrimitive
                val mapped = when {
                    primitive.isString -> value.asString
                    primitive.isBoolean -> value.asBoolean.toString()
                    primitive.isNumber -> value.asNumber.toString()
                    else -> null
                }
                if (mapped != null) metadata[key] = mapped
            }

        return OpenCodeEvent.PermissionAsked(id, sessionId, permission, patterns, metadata)
    }

    private fun parseSessionStatus(props: JsonObject): OpenCodeEvent.SessionBusy? {
        val sessionId = props.getStringOrNull("sessionID") ?: return null
        val statusType = props.getObjectOrNull("status")?.getStringOrNull("type") ?: return null
        return if (statusType == "busy") OpenCodeEvent.SessionBusy(sessionId) else null
    }

    private fun parseMessagePartUpdated(props: JsonObject): OpenCodeEvent.TurnPatch? {
        val part = props.getObjectOrNull("part") ?: return null
        if (part.getStringOrNull("type") != "patch") return null

        val sessionId = part.getStringOrNull("sessionID") ?: return null
        val files = part.getAsJsonArray("files")
            ?.mapNotNull { element ->
                if (!element.isJsonPrimitive) return@mapNotNull null
                element.asString
            }
            ?: emptyList()

        return OpenCodeEvent.TurnPatch(sessionId, files)
    }

    private fun parseSessionIdle(props: JsonObject): OpenCodeEvent.SessionIdle? {
        val sessionId = props.getStringOrNull("sessionID") ?: return null
        return OpenCodeEvent.SessionIdle(sessionId)
    }

    private fun JsonObject.getStringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun JsonObject.getIntOrNull(key: String): Int? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.getObjectOrNull(key: String): JsonObject? {
        val element = get(key) ?: return null
        if (!element.isJsonObject) return null
        return element.asJsonObject
    }
}
