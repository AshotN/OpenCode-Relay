package com.ashotn.opencode.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "OpenCodeSettings",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class OpenCodeSettings : PersistentStateComponent<OpenCodeSettings.State> {

    data class State(
        var serverPort: Int = 4096,
        var executablePath: String = "",
        var inlineDiffEnabled: Boolean = true,
        var diffTraceEnabled: Boolean = false,
        var diffTraceHistoryEnabled: Boolean = false,
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

    companion object {
        fun getInstance(project: Project): OpenCodeSettings =
            project.getService(OpenCodeSettings::class.java)
    }
}
