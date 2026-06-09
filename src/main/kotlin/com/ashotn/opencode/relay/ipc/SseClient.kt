package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.api.event.EventStreamClient
import com.ashotn.opencode.relay.util.getIntOrNull
import com.ashotn.opencode.relay.util.getObjectOrNull
import com.ashotn.opencode.relay.util.getStringOrNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.net.ConnectException
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects to the OpenCode server's durable SSE event stream (`GET /global/event`) and
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
    private val directory: String? = null,
    authorizationHeaderProvider: () -> String? = { null },
    private val onAuthenticationFailure: (() -> Unit)? = null,
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
    private val eventStreamClient = EventStreamClient(
        connectTimeoutMs = CONNECT_TIMEOUT_MS,
        readTimeoutMs = READ_TIMEOUT_MS,
        authorizationHeaderProvider = authorizationHeaderProvider,
    )

    private var future: Future<*>? = null

    fun start() {
        if (executor.isShutdown) return
        if (!running.compareAndSet(false, true)) return
        future = executor.submit { runLoop() }
    }

    fun stop() {
        running.set(false)
        eventStreamClient.disconnect()
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
            } catch (e: EventStreamClient.AuthenticationException) {
                log.info("SseClient: authentication failed, stopping SSE loop", e)
                running.set(false)
                onAuthenticationFailure?.invoke()
                break
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running.get() || Thread.currentThread().isInterrupted || isExpectedDisconnect(e)) {
                    log.debug("SseClient: connection closed")
                } else if (e is ConnectException) {
                    log.debug("SseClient: connection refused, will retry")
                } else {
                    log.warn("SseClient: connection error, will retry", e)
                }
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
        log.info("SseClient: connecting to /global/event on port $port")
        val dataBuffer = StringBuilder()
        eventStreamClient.consume(port) { line ->
            if (Thread.currentThread().isInterrupted) return@consume

            when {
                line.startsWith("data:") -> {
                    val rawData = line.removePrefix("data:")
                    val dataLine = if (rawData.startsWith(" ")) rawData.substring(1) else rawData
                    if (dataBuffer.isNotEmpty()) {
                        dataBuffer.append('\n')
                    }
                    dataBuffer.append(dataLine)
                }

                line.isEmpty() && dataBuffer.isNotEmpty() -> {
                    dispatchRaw(dataBuffer.toString())
                    dataBuffer.clear()
                }
            }
        }

        if (dataBuffer.isNotEmpty()) {
            dispatchRaw(dataBuffer.toString())
            dataBuffer.clear()
        }
    }

    private fun dispatchRaw(json: String) {
        try {
            val rootElement = JsonParser.parseString(json)
            if (!rootElement.isJsonObject) return

            val root = unwrapGlobalEvent(rootElement.asJsonObject) ?: return
            val type = root.getStringOrNull("type") ?: return
            val properties = root.getObjectOrNull("properties") ?: JsonObject()

            val event: OpenCodeEvent? = when (type) {
                "server.connected" -> OpenCodeEvent.ServerConnected
                "server.heartbeat", "sync", "project.updated" -> null
                "session.diff" -> parseSessionDiff(properties)
                "session.idle" -> null
                // OpenCode >= 1.16 exposes turn diffs through user message summaries.
                "message.updated" -> parseMessageUpdated(properties)
                // Any lifecycle change can alter the session list/order/title, so all refresh the hierarchy.
                "session.created", "session.updated", "session.deleted" -> parseSessionLifecycleChanged(properties)
                "session.status" -> parseSessionStatus(properties)
                // OpenCode < 1.16 emitted patch parts that scoped touched files for session.diff.
                "message.part.updated" -> parseMessagePartUpdated(properties)
                "mcp.tools.changed" -> parseMcpToolsChanged(properties)
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

    private fun isExpectedDisconnect(e: Exception): Boolean {
        if (e !is IOException) return false
        val message = e.message?.lowercase() ?: return false
        return message.contains("stream is closed") || message.contains("socket closed")
    }

    private fun unwrapGlobalEvent(root: JsonObject): JsonObject? {
        val payload = root.getObjectOrNull("payload") ?: return root
        val eventDirectory = root.getStringOrNull("directory")
        val expectedDirectory = directory
        if (eventDirectory != null && expectedDirectory != null && !sameDirectory(eventDirectory, expectedDirectory)) {
            return null
        }
        return payload
    }

    private fun sameDirectory(left: String, right: String): Boolean =
        normalizeDirectoryPath(left) == normalizeDirectoryPath(right)

    private fun normalizeDirectoryPath(directory: String): Path =
        runCatching { Path.of(directory).toRealPath() }
            .getOrElse { Path.of(directory).toAbsolutePath().normalize() }

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
            val diffText = PatchDiffTextParser.parse(obj)
            val additions = obj.getIntOrNull("additions") ?: 0
            val deletions = obj.getIntOrNull("deletions") ?: 0
            val statusRaw = obj.getStringOrNull("status") ?: "modified"
            val status = SessionDiffStatus.fromWire(statusRaw)
            if (status == SessionDiffStatus.UNKNOWN) {
                log.warn("SseClient: unknown session.diff status '$statusRaw' for file=$file")
            }

            OpenCodeEvent.SessionDiffFile(file, diffText.before, diffText.after, additions, deletions, status)
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

    private fun parseSessionStatus(props: JsonObject): OpenCodeEvent.SessionStatus? {
        val sessionId = props.getStringOrNull("sessionID") ?: return null
        val status = props.get("status") ?: return null
        val statusType = status.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.getStringOrNull("type")
            ?: return null
        val parsedStatus = when (statusType) {
            "busy" -> OpenCodeEvent.SessionStatusType.BUSY
            "idle" -> OpenCodeEvent.SessionStatusType.IDLE
            "retry" -> OpenCodeEvent.SessionStatusType.RETRY
            else -> {
                log.warn("SseClient: unknown session.status type '$statusType' for session=$sessionId")
                return null
            }
        }
        return OpenCodeEvent.SessionStatus(sessionId, parsedStatus)
    }

    private fun parseSessionLifecycleChanged(props: JsonObject): OpenCodeEvent.SessionLifecycleChanged? {
        val sessionId = props.getStringOrNull("sessionID")
            ?: props.getObjectOrNull("info")?.getStringOrNull("id")
            ?: return null
        return OpenCodeEvent.SessionLifecycleChanged(sessionId)
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

    // OpenCode >= 1.16: message.updated only tells us which user message has diffs;
    // the actual patch content is fetched later via /session/{id}/diff?messageID=...
    private fun parseMessageUpdated(props: JsonObject): OpenCodeEvent.MessageDiffAvailable? {
        val info = props.getObjectOrNull("info") ?: return null
        if (info.getStringOrNull("role") != "user") return null

        val sessionId = props.getStringOrNull("sessionID")
            ?: info.getStringOrNull("sessionID")
            ?: return null
        val messageId = info.getStringOrNull("id") ?: return null
        val diffs = info.getObjectOrNull("summary")
            ?.get("diffs")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?: return null
        val files = diffs.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            element.asJsonObject.getStringOrNull("file")?.takeIf { it.isNotBlank() }
        }
        if (files.isEmpty()) return null

        return OpenCodeEvent.MessageDiffAvailable(sessionId, messageId, files)
    }

    private fun parseMcpToolsChanged(props: JsonObject): OpenCodeEvent.McpToolsChanged? {
        val server = props.getStringOrNull("server") ?: return null
        return OpenCodeEvent.McpToolsChanged(server)
    }

}
