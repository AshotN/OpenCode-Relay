package com.ashotn.opencode.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "OpenCodeSettings",
    storages = [Storage("opencode.xml")]
)
class OpenCodeSettings : PersistentStateComponent<OpenCodeSettings.State> {

    data class State(
        var serverPort: Int = 4096,
        var executablePath: String = ""
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

    companion object {
        fun getInstance(project: Project): OpenCodeSettings =
            project.getService(OpenCodeSettings::class.java)
    }
}
