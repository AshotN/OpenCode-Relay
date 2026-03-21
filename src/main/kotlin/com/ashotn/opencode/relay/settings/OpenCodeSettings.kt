package com.ashotn.opencode.relay.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "OpenCodeRelaySettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class OpenCodeSettings : PersistentStateComponent<OpenCodeSettings.State> {

    enum class TerminalEngine {
        /** JBTerminalWidget (classic terminal plugin, works on all supported IDE versions). */
        CLASSIC,
        /** Parked reworked terminal option kept for easy re-enable later. */
        REWORKED,
    }

    data class State(
        var serverPort: Int = 4096,
        var executablePath: String = "",
        var inlineDiffEnabled: Boolean = true,
        var diffTraceEnabled: Boolean = false,
        var diffTraceHistoryEnabled: Boolean = false,
        var inlineTerminalEnabled: Boolean = true,
        var terminalEngine: TerminalEngine = TerminalEngine.CLASSIC,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var serverPort: Int
        get() = state.serverPort
        set(value) { state.serverPort = value }

    var executablePath: String
        get() = state.executablePath
        set(value) { state.executablePath = value }

    var inlineDiffEnabled: Boolean
        get() = state.inlineDiffEnabled
        set(value) { state.inlineDiffEnabled = value }

    var diffTraceEnabled: Boolean
        get() = state.diffTraceEnabled
        set(value) { state.diffTraceEnabled = value }

    var diffTraceHistoryEnabled: Boolean
        get() = state.diffTraceHistoryEnabled
        set(value) { state.diffTraceHistoryEnabled = value }

    var inlineTerminalEnabled: Boolean
        get() = state.inlineTerminalEnabled
        set(value) { state.inlineTerminalEnabled = value }

    var terminalEngine: TerminalEngine
        get() = state.terminalEngine
        set(value) { state.terminalEngine = value }

    companion object {
        fun getInstance(project: Project): OpenCodeSettings =
            project.getService(OpenCodeSettings::class.java)
    }
}
