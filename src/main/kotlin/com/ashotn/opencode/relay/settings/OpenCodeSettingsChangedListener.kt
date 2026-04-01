package com.ashotn.opencode.relay.settings

import com.intellij.util.messages.Topic

data class OpenCodeSettingsSnapshot(
    val serverPort: Int,
    val executablePath: String,
    val inlineDiffEnabled: Boolean,
    val diffTraceEnabled: Boolean,
    val diffTraceHistoryEnabled: Boolean,
    val inlineTerminalEnabled: Boolean,
    val sessionsSectionVisible: Boolean,
    val terminalEngine: OpenCodeSettings.TerminalEngine,
)

fun interface OpenCodeSettingsChangedListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("OpenCode Settings Changed", OpenCodeSettingsChangedListener::class.java)
    }

    fun onSettingsChanged(oldSettings: OpenCodeSettingsSnapshot, newSettings: OpenCodeSettingsSnapshot)
}
