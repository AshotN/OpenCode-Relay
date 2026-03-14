package com.ashotn.opencode.relay.ipc

import com.intellij.util.messages.Topic

/**
 * Published on the project message bus when an MCP server reports its tool list
 * has changed ([OpenCodeEvent.McpToolsChanged] via SSE).
 *
 * This is the best available proxy for MCP connection status changes, since the
 * server does not emit a dedicated connect/disconnect event.
 */
fun interface McpChangedListener {

    fun onMcpChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<McpChangedListener> =
            Topic.create("OpenCode MCP Changed", McpChangedListener::class.java)
    }
}
