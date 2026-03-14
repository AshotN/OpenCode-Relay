package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.diagnostic.Logger

private val log = Logger.getInstance("com.ashotn.opencode.relay.terminal.ProcessKill")

/**
 * Forcibly kills [handle] and its entire descendant process tree.
 * Safe to call with `null` (no-op) or on an already-dead process.
 */
internal fun killProcessTree(handle: ProcessHandle?) {
    handle ?: return
    try {
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
    } catch (e: Exception) {
        log.debug("Failed to kill process tree for PID ${handle.pid()}", e)
    }
}
