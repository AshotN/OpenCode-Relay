package com.ashotn.opencode.companion

import com.intellij.util.messages.Topic

fun interface ResolvedInfoChangedListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("OpenCode Resolved Info Changed", ResolvedInfoChangedListener::class.java)
    }

    fun onResolvedInfoChanged(info: OpenCodeInfo?)
}
