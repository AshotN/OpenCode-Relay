package com.ashotn.opencode.relay.ipc

import com.intellij.util.messages.Topic

/**
 * Published on the project message bus when an MCP server's connection status
 * has changed ([OpenCodeEvent.McpStatusChanged] via SSE, sourced from the
 * server's `mcp.status.changed` event).
 */
fun interface McpChangedListener {

    fun onMcpChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<McpChangedListener> =
            Topic.create("OpenCode MCP Changed", McpChangedListener::class.java)
    }
}
