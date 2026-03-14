package com.ashotn.opencode.relay

import com.intellij.util.messages.Topic

/**
 * Published when the current [OpenCodeInfo] changes.
 */
fun interface OpenCodeInfoChangedListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("OpenCode Info Changed", OpenCodeInfoChangedListener::class.java)
    }

    fun onOpenCodeInfoChanged(info: OpenCodeInfo?)
}
