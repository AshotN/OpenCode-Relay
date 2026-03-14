package com.ashotn.opencode.relay.core.session

internal class PendingSessionSelection {

    private var pendingSessionId: String? = null

    fun shouldDeferSelection(requestedSessionId: String?, sessionExists: (String) -> Boolean): Boolean {
        return when {
            requestedSessionId == null -> {
                pendingSessionId = null
                false
            }

            sessionExists(requestedSessionId) -> {
                pendingSessionId = null
                false
            }

            else -> {
                pendingSessionId = requestedSessionId
                true
            }
        }
    }

    fun consumeIfResolved(knownSessionIds: Set<String>): String? {
        val pending = pendingSessionId
        return if (!pending.isNullOrBlank() && pending in knownSessionIds) {
            pendingSessionId = null
            pending
        } else {
            null
        }
    }

    fun clear() {
        pendingSessionId = null
    }
}