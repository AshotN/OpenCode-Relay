package com.ashotn.opencode.relay.ipc

/**
 * Typed representation of SSE events received from the OpenCode 2.x server.
 *
 * Only the subset of events relevant to the plugin are modelled here.
 * Unknown event types are silently discarded by [SseClient].
 */
sealed class OpenCodeEvent {

    /** server.connected — fired when the SSE stream is attached and ready. */
    data object ServerConnected : OpenCodeEvent()

    /**
     * Canonical session execution state.
     *
     * Sourced from `session.status` (`data.status.type`), `session.idle`,
     * and `session.execution.started`.
     */
    data class SessionStatus(
        val sessionId: String,
        val status: SessionStatusType,
    ) : OpenCodeEvent()

    enum class SessionStatusType {
        IDLE,
        BUSY,
        RETRY,
    }

    /**
     * A session execution finished (`session.execution.succeeded`,
     * `session.execution.failed`, or `session.execution.interrupted`).
     *
     * In OpenCode 2.x the per-message diff summary of v1 is gone, so the end of an
     * execution is the signal that the latest turn's diff snapshot may have changed.
     */
    data class SessionExecutionFinished(val sessionId: String) : OpenCodeEvent()

    /** session lifecycle changed — refresh session hierarchy metadata. */
    data class SessionLifecycleChanged(val sessionId: String) : OpenCodeEvent()

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
     * mcp.status.changed — fired when an MCP server's connection status changed.
     * Used as the signal that the MCP server list may need a refresh.
     */
    data class McpStatusChanged(val serverName: String) : OpenCodeEvent()

    /**
     * permission.asked — fired when OpenCode needs the user to approve a tool
     * call before it proceeds. OpenCode blocks until a reply is posted to
     * POST /api/session/:sessionID/permission/:requestID/reply.
     *
     * @param requestId  Unique permission request ID (per_ prefix)
     * @param sessionId  Session that triggered the request
     * @param permission The permission action, e.g. "external_directory" (v2 `action`)
     * @param patterns   Resource globs describing what will be affected (v2 `resources`)
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
