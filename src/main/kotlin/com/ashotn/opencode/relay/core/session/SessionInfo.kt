package com.ashotn.opencode.relay.core.session

data class SessionInfo(
    val sessionId: String,
    val parentSessionId: String?,
    val title: String,
    val isBusy: Boolean,
    val trackedFileCount: Int,
    val updatedAtMillis: Long,
    /** True if the server has reported a summary for this session, indicating it has messages. */
    val hasMessages: Boolean,
)