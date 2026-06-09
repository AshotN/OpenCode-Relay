package com.ashotn.opencode.relay.integration

import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.SseClient

class OpenCodeTestEventCollector(
    port: Int,
    directory: String? = null,
    private val onCollectedEvent: (OpenCodeEvent) -> Unit = {},
) : AutoCloseable {

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private val events = mutableListOf<OpenCodeEvent>()
    private val sseClient = SseClient(
        port = port,
        directory = directory,
        onEvent = { event ->
            synchronized(lock) {
                events.add(event)
                lock.notifyAll()
            }
            onCollectedEvent(event)
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
        sessionStatusCountLocked(sessionId, OpenCodeEvent.SessionStatusType.IDLE)
    }

    fun sessionStatusCount(sessionId: String, status: OpenCodeEvent.SessionStatusType): Int = synchronized(lock) {
        sessionStatusCountLocked(sessionId, status)
    }

    fun sessionDiffCount(sessionId: String): Int = synchronized(lock) {
        events.count { it is OpenCodeEvent.SessionDiff && it.sessionId == sessionId }
    }

    fun diffSignalCount(sessionId: String): Int = synchronized(lock) {
        events.count {
            (it is OpenCodeEvent.SessionDiff && it.sessionId == sessionId) ||
                    (it is OpenCodeEvent.MessageDiffAvailable && it.sessionId == sessionId)
        }
    }

    fun messageDiffEvents(sessionId: String): List<OpenCodeEvent.MessageDiffAvailable> = synchronized(lock) {
        events.filterIsInstance<OpenCodeEvent.MessageDiffAvailable>()
            .filter { it.sessionId == sessionId }
    }

    fun awaitIdleStatus(sessionId: String, atLeastCount: Int, timeoutMs: Long = 15_000): OpenCodeEvent.SessionStatus =
        awaitSessionStatus(sessionId, OpenCodeEvent.SessionStatusType.IDLE, atLeastCount, timeoutMs)

    fun awaitSessionStatus(
        sessionId: String,
        status: OpenCodeEvent.SessionStatusType,
        atLeastCount: Int,
        timeoutMs: Long = 15_000,
    ): OpenCodeEvent.SessionStatus =
        awaitEvent(
            timeoutMs,
            "session.status ${status.name.lowercase()} for $sessionId count >= $atLeastCount"
        ) { recordedEvents ->
            val matching = recordedEvents.filterIsInstance<OpenCodeEvent.SessionStatus>()
                .filter { it.sessionId == sessionId && it.status == status }
            if (matching.size >= atLeastCount) matching.last() else null
        }

    fun awaitSessionLifecycle(
        sessionId: String,
        atLeastCount: Int,
        timeoutMs: Long = 15_000
    ): OpenCodeEvent.SessionLifecycleChanged =
        awaitEvent(timeoutMs, "session lifecycle event for $sessionId count >= $atLeastCount") { recordedEvents ->
            val matching = recordedEvents.filterIsInstance<OpenCodeEvent.SessionLifecycleChanged>()
                .filter { it.sessionId == sessionId }
            if (matching.size >= atLeastCount) matching.last() else null
        }

    fun awaitSessionDiff(sessionId: String, atLeastCount: Int, timeoutMs: Long = 15_000): OpenCodeEvent.SessionDiff =
        awaitEvent(timeoutMs, "session.diff for $sessionId count >= $atLeastCount") { recordedEvents ->
            val matching = recordedEvents.filterIsInstance<OpenCodeEvent.SessionDiff>()
                .filter { it.sessionId == sessionId }
            if (matching.size >= atLeastCount) matching.last() else null
        }

    fun awaitDiffSignal(sessionId: String, atLeastCount: Int, timeoutMs: Long = 15_000): OpenCodeEvent =
        awaitEvent(timeoutMs, "diff signal for $sessionId count >= $atLeastCount") { recordedEvents ->
            val matching = recordedEvents.filter {
                (it is OpenCodeEvent.SessionDiff && it.sessionId == sessionId) ||
                        (it is OpenCodeEvent.MessageDiffAvailable && it.sessionId == sessionId)
            }
            if (matching.size >= atLeastCount) matching.last() else null
        }

    fun recentEventSummary(limit: Int = 10): String = synchronized(lock) {
        if (events.isEmpty()) return@synchronized "<none>"
        events.takeLast(limit).joinToString(separator = " | ") { event ->
            when (event) {
                is OpenCodeEvent.ServerConnected -> "server.connected"
                is OpenCodeEvent.SessionStatus -> {
                    "session.status(sessionId=${event.sessionId}, type=${event.status.name.lowercase()})"
                }

                is OpenCodeEvent.SessionDiff -> {
                    val files = event.files.joinToString(",") { it.file.substringAfterLast('/') }
                    "session.diff(sessionId=${event.sessionId}, files=$files)"
                }

                is OpenCodeEvent.MessageDiffAvailable -> {
                    val files = event.files.joinToString(",") { it.substringAfterLast('/') }
                    "message.diff(sessionId=${event.sessionId}, messageId=${event.messageId}, files=$files)"
                }

                is OpenCodeEvent.SessionLifecycleChanged -> "session.lifecycle(sessionId=${event.sessionId})"

                else -> event::class.simpleName ?: "UnknownEvent"
            }
        }
    }

    private fun sessionStatusCountLocked(sessionId: String, status: OpenCodeEvent.SessionStatusType): Int =
        events.count {
            it is OpenCodeEvent.SessionStatus &&
                    it.sessionId == sessionId &&
                    it.status == status
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
