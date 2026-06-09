package com.ashotn.opencode.relay.ipc

/**
 * Typed representation of SSE events received from the OpenCode server.
 *
 * Only the subset of events relevant to the plugin are modelled here.
 * Unknown event types are silently discarded by [SseClient].
 */
sealed class OpenCodeEvent {

    /** server.connected — fired when the SSE stream is attached and ready. */
    data object ServerConnected : OpenCodeEvent()

    /**
     * Canonical session execution state from session.status.
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

    /** session lifecycle changed — refresh session hierarchy metadata. */
    data class SessionLifecycleChanged(val sessionId: String) : OpenCodeEvent()

    /** message.part.updated with type == "patch". */
    data class TurnPatch(
        val sessionId: String,
        val files: List<String>,   // absolute paths
    ) : OpenCodeEvent()

    /** message.updated for a user message whose summary contains per-message diffs. */
    data class MessageDiffAvailable(
        val sessionId: String,
        val messageId: String,
        val files: List<String> = emptyList(),
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
     * for the session. The payload contains patch-based entries; the plugin
     * reconstructs before/after text and stores a typed [SessionDiffStatus].
     */
    data class SessionDiff(
        val sessionId: String,
        val files: List<SessionDiffFile>,
        /** OpenCode >= 1.16 diff fetched with messageID; use server `before` as the live turn baseline. */
        val isMessageScoped: Boolean = false,
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
     * mcp.tools.changed — fired when an MCP server reports its tool list has changed.
     * Used as a proxy signal that an MCP server's connection status may have changed.
     */
    data class McpToolsChanged(val serverName: String) : OpenCodeEvent()

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
