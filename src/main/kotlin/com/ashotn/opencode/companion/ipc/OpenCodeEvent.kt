package com.ashotn.opencode.companion.ipc

/**
 * Typed representation of SSE events received from the OpenCode server.
 *
 * Only the subset of events relevant to the plugin are modelled here.
 * Unknown event types are silently discarded by [SseClient].
 */
sealed class OpenCodeEvent {

    /**
     * session.idle — fired when the AI finishes responding (turn complete).
     */
    data class SessionIdle(val sessionId: String) : OpenCodeEvent()

    /** session.created — fired when a new session is created. */
    data class SessionCreated(val sessionId: String) : OpenCodeEvent()

    /**
     * session.busy — fired when the AI starts or resumes work in a session.
     * Used to keep track of the latest active session.
     */
    data class SessionBusy(val sessionId: String) : OpenCodeEvent()

    /** message.part.updated with type == "patch". */
    data class TurnPatch(
        val sessionId: String,
        val files: List<String>,   // absolute paths
    ) : OpenCodeEvent()

    /**
     * permission.replied — fired when any client (plugin, TUI, CLI) resolves a
     * pending permission request. Lets us drain the queue even when the reply
     * didn't come from this plugin.
     */
    data class PermissionReplied(
        val requestId: String,
        val sessionId: String,
        val reply: PermissionReply,
    ) : OpenCodeEvent()

    /**
     * session.diff — fired after every tool execution with the cumulative diff
     * for the session. Each entry carries before/after content and a typed
     * [SessionDiffStatus].
     */
    data class SessionDiff(
        val sessionId: String,
        val files: List<SessionDiffFile>,
    ) : OpenCodeEvent()

    data class SessionDiffFile(
        val file: String,       // project-relative path
        val before: String,
        val after: String,
        val additions: Int,
        val deletions: Int,
        val status: SessionDiffStatus,
    )

    /**
     * permission.asked — fired when OpenCode needs the user to approve a tool
     * call before it proceeds. OpenCode blocks until a reply is posted to
     * POST /permission/:requestId/reply.
     *
     * @param requestId  Unique permission request ID (per_ prefix)
     * @param sessionId  Session that triggered the request
     * @param permission Human-readable permission name, e.g. "execute_bash_command"
     * @param patterns   Glob patterns describing what will be affected
     * @param metadata   Extra context from the tool, e.g. the shell command being run
     */
    data class PermissionAsked(
        val requestId: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String>,
        val metadata: Map<String, String>,
    ) : OpenCodeEvent()
}

/**
 * The before/after content for a single file within a session diff.
 *
 * Maps to the FileDiff type in the OpenCode TypeScript SDK:
 * { file, before, after, additions, deletions }
 */
data class FileDiff(
    val file: String,
    val before: String,
    val after: String,
    val additions: Int,
    val deletions: Int,
)
