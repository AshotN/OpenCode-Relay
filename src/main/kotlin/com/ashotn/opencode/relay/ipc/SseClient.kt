package com.ashotn.opencode.relay.ipc

import com.ashotn.opencode.relay.api.event.EventStreamClient
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
 * Consumes the OpenCode 2.x SSE event stream.
 *
 * Connects to the OpenCode server's durable SSE event stream (`GET /api/event`) and
 * dispatches typed [OpenCodeEvent]s to [onEvent]. The client transparently reconnects
 * with exponential backoff when the stream drops; a 401 response stops the loop and
 * reports the failure via [onAuthenticationFailure] (the caller decides how to recover).
 *
 * Event envelope (v2): each `data:` line carries a single JSON object of the shape
 * `{"id", "created", "type", "location"?, "data": {...}}`. Events whose
 * `location.directory` differs from [directory] are dropped (the stream is global to
 * the server, but this plugin only tracks one project).
 *
 * All callbacks ([onEvent]) are invoked on the client's internal reader thread.
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
        log.info("SseClient: connecting to /api/event on port $port")
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

            val root = rootElement.asJsonObject
            if (!isForThisDirectory(root)) return

            val type = root.getStringOrNull("type") ?: return
            val payload = root.getObjectOrNull("data") ?: JsonObject()

            val event: OpenCodeEvent? = when (type) {
                "server.connected" -> OpenCodeEvent.ServerConnected
                "session.status" -> parseSessionStatus(payload)
                // session.idle is a standalone idle marker in v2.
                "session.idle" -> parseSessionIdle(payload)
                // The start of an execution is a reliable busy signal.
                "session.execution.started" -> parseExecutionStarted(payload)
                // A finished execution is when the latest turn's diff can change.
                "session.execution.succeeded",
                "session.execution.failed",
                "session.execution.interrupted",
                -> parseExecutionFinished(payload)
                // Any lifecycle change can alter the session list/order/title, so all refresh the hierarchy.
                "session.created",
                "session.deleted",
                "session.forked",
                "session.moved",
                "session.renamed",
                "session.metadata.updated",
                -> parseSessionLifecycleChanged(payload)
                "permission.asked" -> parsePermissionAsked(payload)
                "permission.replied" -> parsePermissionReplied(payload)
                "mcp.status.changed" -> parseMcpStatusChanged(payload)
                "project.updated" -> null
                else -> {
                    if (unknownEventTypes.add(type)) {
                        log.warn("SseClient: unknown event type ignored: $type")
                    }
                    null
                }
            }

            if (event != null) {
                onEvent(event)
                derivedIdleStatus(event)?.let { onEvent(it) }
            }
        } catch (e: Exception) {
            log.warn("SseClient: failed to parse event jsonLength=${json.length}", e)
        }
    }

    /**
     * v2 does not emit `session.idle` or `session.status` when a turn completes —
     * only `session.execution.{succeeded,failed,interrupted}` marks the end of a
     * turn (verified live against v2.0.32) — so the idle status is derived from
     * the finished execution.
     */
    private fun derivedIdleStatus(event: OpenCodeEvent): OpenCodeEvent.SessionStatus? =
        when (event) {
            is OpenCodeEvent.SessionExecutionFinished ->
                OpenCodeEvent.SessionStatus(event.sessionId, OpenCodeEvent.SessionStatusType.IDLE)
            else -> null
        }

    /**
     * v2 events carry an optional `location.directory`. When both the event and this
     * client have a directory, events from other directories are dropped.
     */
    private fun isForThisDirectory(root: JsonObject): Boolean {
        val eventDirectory = root.getObjectOrNull("location")?.getStringOrNull("directory")
        val expectedDirectory = directory
        if (eventDirectory == null || expectedDirectory == null) return true
        return sameDirectory(eventDirectory, expectedDirectory)
    }

    private fun sameDirectory(left: String, right: String): Boolean =
        normalizeDirectoryPath(left) == normalizeDirectoryPath(right)

    private fun normalizeDirectoryPath(directory: String): Path =
        runCatching { Path.of(directory).toRealPath() }
            .getOrElse { Path.of(directory).toAbsolutePath().normalize() }

    private fun parsePermissionReplied(payload: JsonObject): OpenCodeEvent.PermissionReplied? {
        val requestId = payload.getStringOrNull("requestID") ?: return null
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        val replyRaw = payload.getStringOrNull("reply") ?: return null
        val reply = PermissionReply.fromWire(replyRaw)
        if (reply == null) {
            log.warn("SseClient: unknown permission reply '$replyRaw' for requestID=$requestId")
            return null
        }
        return OpenCodeEvent.PermissionReplied(requestId, sessionId, reply)
    }

    private fun parsePermissionAsked(payload: JsonObject): OpenCodeEvent.PermissionAsked? {
        val id = payload.getStringOrNull("id") ?: return null
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        // v2 names the fields `action` and `resources`; the plugin model keeps the
        // v1-oriented names `permission` and `patterns`.
        val permission = payload.getStringOrNull("action") ?: return null

        val patterns = payload.getAsJsonArray("resources")
            ?.mapNotNull { element ->
                if (!element.isJsonPrimitive) return@mapNotNull null
                element.asString
            }
            ?: emptyList()

        val metadata = mutableMapOf<String, String>()
        payload.getAsJsonObject("metadata")
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

    private fun parseSessionStatus(payload: JsonObject): OpenCodeEvent.SessionStatus? {
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        val status = payload.get("status") ?: return null
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

    private fun parseSessionIdle(payload: JsonObject): OpenCodeEvent.SessionStatus? {
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        return OpenCodeEvent.SessionStatus(sessionId, OpenCodeEvent.SessionStatusType.IDLE)
    }

    private fun parseExecutionStarted(payload: JsonObject): OpenCodeEvent.SessionStatus? {
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        return OpenCodeEvent.SessionStatus(sessionId, OpenCodeEvent.SessionStatusType.BUSY)
    }

    private fun parseExecutionFinished(payload: JsonObject): OpenCodeEvent.SessionExecutionFinished? {
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        return OpenCodeEvent.SessionExecutionFinished(sessionId)
    }

    private fun parseSessionLifecycleChanged(payload: JsonObject): OpenCodeEvent.SessionLifecycleChanged? {
        val sessionId = payload.getStringOrNull("sessionID") ?: return null
        return OpenCodeEvent.SessionLifecycleChanged(sessionId)
    }

    private fun parseMcpStatusChanged(payload: JsonObject): OpenCodeEvent.McpStatusChanged? {
        val server = payload.getStringOrNull("server") ?: return null
        return OpenCodeEvent.McpStatusChanged(server)
    }

    private fun isExpectedDisconnect(e: Exception): Boolean {
        if (e !is IOException) return false
        val message = e.message?.lowercase() ?: return false
        return message.contains("stream is closed") || message.contains("socket closed")
    }

}
