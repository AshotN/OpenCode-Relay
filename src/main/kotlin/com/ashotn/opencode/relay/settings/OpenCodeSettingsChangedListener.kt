package com.ashotn.opencode.relay.settings

import com.intellij.util.messages.Topic

data class OpenCodeSettingsSnapshot(
    val serverPort: Int,
    val serverHostname: String,
    val serverMdnsEnabled: Boolean,
    val serverMdnsDomain: String,
    val serverCorsOrigins: String,
    val serverAuthUsername: String,
    val protectPluginLaunchedServerWithAuth: Boolean,
    val serverEnvironmentVariables: List<OpenCodeSettings.EnvironmentVariable>,
    val executablePath: String,
    val inlineDiffEnabled: Boolean,
    val relayPromptInjectionEnabled: Boolean,
    val diffTraceEnabled: Boolean,
    val diffTraceHistoryEnabled: Boolean,
    val inlineTerminalEnabled: Boolean,
    val sessionsSectionVisible: Boolean,
    val terminalEngine: OpenCodeSettings.TerminalEngine,
    val braveModeEnabled: Boolean,
)

fun interface OpenCodeSettingsChangedListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("OpenCode Settings Changed", OpenCodeSettingsChangedListener::class.java)
    }

    fun onSettingsChanged(oldSettings: OpenCodeSettingsSnapshot, newSettings: OpenCodeSettingsSnapshot)
}
