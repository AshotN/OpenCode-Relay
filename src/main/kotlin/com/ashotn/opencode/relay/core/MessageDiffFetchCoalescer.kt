package com.ashotn.opencode.relay.core

/**
 * Coalesces concurrent diff snapshot fetches so at most one fetch runs per key.
 *
 * In OpenCode 2.x the live snapshot is the latest turn of a session (no per-message
 * diff events), so [liveTurnKey] keys fetches by session. When a fetch is already
 * in flight for a key, [tryStart] reports that and the caller may rely on
 * [finish] to learn that a follow-up fetch is warranted.
 */
internal class MessageDiffFetchCoalescer {

    companion object {
        private const val LIVE_TURN = "latest"

        /** Key used for the latest-turn snapshot fetch of a session. */
        fun liveTurnKey(sessionId: String): String = key(sessionId, LIVE_TURN)

        fun key(sessionId: String, turn: String): String = "$sessionId:$turn"
    }

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()
    private val pendingKeys = mutableSetOf<String>()

    fun tryStart(key: String): Boolean = synchronized(lock) {
        if (inFlight.add(key)) {
            true
        } else {
            pendingKeys.add(key)
            false
        }
    }

    /**
     * Releases the key and reports whether a follow-up fetch is pending for it.
     * When [allowPending] is false (the listener generation went stale), pending
     * requests are discarded instead.
     */
    fun finish(key: String, allowPending: Boolean): Boolean = synchronized(lock) {
        inFlight.remove(key)
        if (allowPending) {
            // Set.remove reports whether a follow-up fetch was pending.
            pendingKeys.remove(key)
        } else {
            pendingKeys.remove(key)
            false
        }
    }

    fun cancel(key: String) = synchronized(lock) {
        inFlight.remove(key)
        pendingKeys.remove(key)
    }

    fun clear() = synchronized(lock) {
        inFlight.clear()
        pendingKeys.clear()
    }
}
