package com.ashotn.opencode

import com.ashotn.opencode.diff.OpenCodeDiffService
import com.ashotn.opencode.tui.OpenCodeTuiClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class OpenCodePlugin(private val project: Project) : Disposable {

    // --- Listeners ---

    private val listeners = CopyOnWriteArrayList<ServerStateListener>()

    fun addListener(listener: ServerStateListener) = listeners.add(listener)
    fun removeListener(listener: ServerStateListener) = listeners.remove(listener)

    // --- ServerManager delegation ---

    private val serverManager = ServerManager(project) { state ->
        listeners.forEach { it.onStateChanged(state) }
        if (state == ServerState.READY) {
            val port = com.ashotn.opencode.settings.OpenCodeSettings.getInstance(project).serverPort
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                OpenCodeDiffService.getInstance(project).startListening(port)
                OpenCodeTuiClient.getInstance(project).setPort(port)
            }
        } else if (state == ServerState.STOPPED) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                OpenCodeDiffService.getInstance(project).stopListening()
                OpenCodeTuiClient.getInstance(project).setPort(0)
            }
        }
    }

    val isRunning: Boolean get() = serverManager.isRunning
    val ownsProcess: Boolean get() = serverManager.ownsProcess

    @Volatile private var overrideState: ServerState? = null
    val serverState: ServerState get() = overrideState ?: serverManager.serverState

    private fun broadcastState(state: ServerState) {
        listeners.forEach { it.onStateChanged(state) }
    }

    fun startPolling(port: Int, intervalSeconds: Long = 10L) = serverManager.startPolling(port, intervalSeconds)
    fun checkPort(port: Int) = serverManager.checkPort(port)
    fun startServer(port: Int, executablePath: String) = serverManager.startServer(port, executablePath)
    fun stopServer() = serverManager.stopServer()
    fun resetConnection() {
        val port = com.ashotn.opencode.settings.OpenCodeSettings.getInstance(project).serverPort
        overrideState = ServerState.RESETTING
        broadcastState(ServerState.RESETTING)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val diffService = OpenCodeDiffService.getInstance(project)
            diffService.stopListening()
            overrideState = null
            diffService.startListening(port)
            // Broadcast the real underlying state now that the override is cleared,
            // so listeners (e.g. the TUI panel) can react and restore themselves.
            val realState = serverManager.serverState
            ApplicationManager.getApplication().invokeLater {
                broadcastState(realState)
            }
        }
    }

    // --- Disposable ---

    override fun dispose() {
        listeners.clear()
        serverManager.dispose()
    }

    companion object {
        fun getInstance(project: Project): OpenCodePlugin =
            project.getService(OpenCodePlugin::class.java)
    }
}
