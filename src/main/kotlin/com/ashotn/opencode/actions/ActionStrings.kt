package com.ashotn.opencode.actions

enum class ActionStrings(
    val text: String,
    val disabledText: String,
    val description: String,
    val disabledDescription: String,
) {
    OPEN_TERMINAL(
        text = "Open in Terminal",
        disabledText = "Open in Terminal (OpenCode must be running)",
        description = "Run opencode attach in a new terminal window",
        disabledDescription = "OpenCode must be running to attach a terminal",
    ),
    OPEN_BROWSER(
        text = "Open in Browser",
        disabledText = "Open in Browser (OpenCode must be running)",
        description = "Open the OpenCode web UI in your browser",
        disabledDescription = "OpenCode must be running to open the web UI",
    ),
    OPEN_SETTINGS(
        text = "OpenCode Settings",
        disabledText = "OpenCode Settings",
        description = "Open OpenCode settings",
        disabledDescription = "Open OpenCode settings",
    ),
}
