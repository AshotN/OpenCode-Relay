package com.ashotn.opencode

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class OpenCodePlugin(project: Project) : Disposable {

    // --- Listeners ---

    private val listeners = CopyOnWriteArrayList<ServerStateListener>()

    fun addListener(listener: ServerStateListener) = listeners.add(listener)
    fun removeListener(listener: ServerStateListener) = listeners.remove(listener)

    // --- ServerManager delegation ---

    private val serverManager = ServerManager(project) { state ->
        listeners.forEach { it.onStateChanged(state) }
    }

    val isRunning: Boolean get() = serverManager.isRunning
    val ownsProcess: Boolean get() = serverManager.ownsProcess

    fun startPolling(port: Int, intervalSeconds: Long = 10L) = serverManager.startPolling(port, intervalSeconds)
    fun checkPort(port: Int) = serverManager.checkPort(port)
    fun startServer(port: Int, executablePath: String) = serverManager.startServer(port, executablePath)
    fun stopServer() = serverManager.stopServer()

    // --- Disposable ---

    override fun dispose() = serverManager.dispose()

    companion object {
        fun getInstance(project: Project): OpenCodePlugin =
            project.getService(OpenCodePlugin::class.java)
    }
}
