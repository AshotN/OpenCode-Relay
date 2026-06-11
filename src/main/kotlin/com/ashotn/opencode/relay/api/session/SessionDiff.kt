package com.ashotn.opencode.relay.api.session

import com.ashotn.opencode.relay.ipc.SessionDiffStatus

/** Diff snapshot fetched from the OpenCode HTTP API. */
data class SessionDiffSnapshot(
    val sessionId: String,
    val files: List<SessionDiffFile>,
)

data class SessionDiffFile(
    val file: String, // project-relative path
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int,
    val status: SessionDiffStatus,
)
