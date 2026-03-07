package com.ashotn.opencode.ipc

import com.intellij.util.messages.Topic

/**
 * Published on the project message bus whenever the pending permission request
 * changes (a new one arrives, or the current one is resolved/dequeued).
 *
 * The callback argument is the next pending [OpenCodeEvent.PermissionAsked],
 * or null when there are no queued requests.
 */
fun interface PermissionChangedListener {
    fun onPermissionChanged(event: OpenCodeEvent.PermissionAsked?)

    companion object {
        val TOPIC: Topic<PermissionChangedListener> = Topic.create(
            "OpenCode Permission Changed",
            PermissionChangedListener::class.java,
        )
    }
}
