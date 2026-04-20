package com.ashotn.opencode.relay.integration

import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.SseClient

class OpenCodeTestEventCollector(
    port: Int,
) : AutoCloseable {

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val events = mutableListOf<OpenCodeEvent>()
    private val sseClient = SseClient(
        port = port,
        onEvent = { event ->
            synchronized(lock) {
                events.add(event)
                lock.notifyAll()
            }
        },
    )

    init {
        sseClient.start()
    }

    fun awaitConnected(timeoutMs: Long = 5_000) {
        awaitEvent(timeoutMs, "server.connected") { recordedEvents ->
            recordedEvents.filterIsInstance<OpenCodeEvent.ServerConnected>().lastOrNull()
        }
    }

    fun sessionIdleCount(sessionId: String): Int = synchronized(lock) {
        events.count { it is OpenCodeEvent.SessionIdle && it.sessionId == sessionId }
    }

    fun sessionDiffCount(sessionId: String): Int = synchronized(lock) {
        events.count { it is OpenCodeEvent.SessionDiff && it.sessionId == sessionId }
    }

    fun awaitSessionIdle(sessionId: String, atLeastCount: Int, timeoutMs: Long = 15_000): OpenCodeEvent.SessionIdle =
        awaitEvent(timeoutMs, "session.idle for $sessionId count >= $atLeastCount") { recordedEvents ->
            val matching = recordedEvents.filterIsInstance<OpenCodeEvent.SessionIdle>()
                .filter { it.sessionId == sessionId }
            if (matching.size >= atLeastCount) matching.last() else null
        }

    fun awaitSessionDiff(sessionId: String, atLeastCount: Int, timeoutMs: Long = 15_000): OpenCodeEvent.SessionDiff =
        awaitEvent(timeoutMs, "session.diff for $sessionId count >= $atLeastCount") { recordedEvents ->
            val matching = recordedEvents.filterIsInstance<OpenCodeEvent.SessionDiff>()
                .filter { it.sessionId == sessionId }
            if (matching.size >= atLeastCount) matching.last() else null
        }

    fun recentEventSummary(limit: Int = 10): String = synchronized(lock) {
        if (events.isEmpty()) return@synchronized "<none>"
        events.takeLast(limit).joinToString(separator = " | ") { event ->
            when (event) {
                is OpenCodeEvent.ServerConnected -> "server.connected"
                is OpenCodeEvent.SessionIdle -> "session.idle(sessionId=${event.sessionId})"
                is OpenCodeEvent.SessionDiff -> {
                    val files = event.files.joinToString(",") { it.file.substringAfterLast('/') }
                    "session.diff(sessionId=${event.sessionId}, files=$files)"
                }

                else -> event::class.simpleName ?: "UnknownEvent"
            }
        }
    }

    private fun <T> awaitEvent(timeoutMs: Long, description: String, matcher: (List<OpenCodeEvent>) -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                matcher(events)?.let { return it }

                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0) {
                    error("Timed out waiting for $description. Recorded events: ${events.map { it::class.simpleName }}")
                }

                lock.wait(remainingMs)
            }
        }
    }

    override fun close() {
        sseClient.stop()
    }
}
