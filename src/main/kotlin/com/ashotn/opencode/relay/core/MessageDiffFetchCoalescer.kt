package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.ipc.OpenCodeEvent

internal class MessageDiffFetchCoalescer {
    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val pendingByKey = mutableMapOf<String, OpenCodeEvent.MessageDiffAvailable>()

    fun key(event: OpenCodeEvent.MessageDiffAvailable): String = key(event.sessionId, event.messageId)

    fun key(sessionId: String, messageId: String): String = "$sessionId:$messageId"

    fun tryStart(event: OpenCodeEvent.MessageDiffAvailable): Boolean = synchronized(lock) {
        val key = key(event)
        if (inFlight.add(key)) {
            true
        } else {
            pendingByKey[key] = event
            false
        }
    }

    fun finish(key: String, allowPending: Boolean): OpenCodeEvent.MessageDiffAvailable? = synchronized(lock) {
        val pending = if (allowPending) pendingByKey.remove(key) else null
        if (pending == null) {
            inFlight.remove(key)
            if (!allowPending) pendingByKey.remove(key)
        }
        pending
    }

    fun cancel(key: String) = synchronized(lock) {
        inFlight.remove(key)
        pendingByKey.remove(key)
    }

    fun clear() = synchronized(lock) {
        inFlight.clear()
        pendingByKey.clear()
    }
}
