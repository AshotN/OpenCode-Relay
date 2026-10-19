package com.ashotn.opencode.relay.core.session

data class SessionInfo(
    val sessionId: String,
    val parentSessionId: String?,
    val title: String,
    val isBusy: Boolean,
    val trackedFileCount: Int?,
    val updatedAtMillis: Long,
    /** True if the session has content — local diff state or a loaded file count. */
    val hasMessages: Boolean,
)
