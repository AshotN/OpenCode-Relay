package com.ashotn.opencode.companion.core.session

import com.intellij.util.messages.Topic

/**
 * Published on the project message bus whenever session-level diff metadata
 * changes (busy/idle state, tracked file counts, session list updates).
 */
fun interface SessionStateChangedListener {

    fun onSessionStateChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<SessionStateChangedListener> =
            Topic.create("OpenCode Session State Changed", SessionStateChangedListener::class.java)
    }
}