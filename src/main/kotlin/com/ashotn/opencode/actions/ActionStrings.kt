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
    STOP_SERVER(
        text = "Stop OpenCode",
        disabledText = "Stop OpenCode (available only for plugin-launched server)",
        description = "Stop the OpenCode server",
        disabledDescription = "Stop is available only when OpenCode was launched by this plugin",
    ),
    NEW_SESSION(
        text = "New Session",
        disabledText = "New Session (OpenCode must be running)",
        description = "Create a new OpenCode session and switch the TUI to it",
        disabledDescription = "OpenCode must be running to create a new session",
    ),
    CLEAR_INSTANCE(
        text = "Clear Session",
        disabledText = "Clear Session (no session selected)",
        description = "Clear the selected session and remove all diff highlights",
        disabledDescription = "No session is currently selected",
    ),
    OPEN_SETTINGS(
        text = "OpenCode Settings",
        disabledText = "OpenCode Settings",
        description = "Open OpenCode settings",
        disabledDescription = "Open OpenCode settings",
    ),
    SEND_SELECTION(
        text = "Send Selection to OpenCode",
        disabledText = "Send Selection to OpenCode (OpenCode must be running)",
        description = "Append a file reference for the selected lines to OpenCode TUI",
        disabledDescription = "OpenCode must be running to send a selection",
    ),
    SEND_FILE(
        text = "Send File to OpenCode",
        disabledText = "Send File to OpenCode (OpenCode must be running)",
        description = "Append a file reference to OpenCode TUI",
        disabledDescription = "OpenCode must be running to send a file",
    ),
    SEND_FOLDER(
        text = "Send Folder to OpenCode",
        disabledText = "Send Folder to OpenCode (OpenCode must be running)",
        description = "Append a folder reference to OpenCode TUI",
        disabledDescription = "OpenCode must be running to send a folder",
    ),
    RESET_PLUGIN(
        text = "Reset OpenCode",
        disabledText = "Reset OpenCode (server must be running or starting)",
        description = "Disconnect from the server, reset all plugin state, and reconnect",
        disabledDescription = "Reset is only available when OpenCode is running or starting",
    ),
}
