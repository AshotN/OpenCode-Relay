package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.core.OpenCodeCoreService
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.tui.OpenCodeTuiClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
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
            val port = OpenCodeSettings.getInstance(project).serverPort
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                OpenCodeCoreService.getInstance(project).startListening(port)
                OpenCodeTuiClient.getInstance(project).setPort(port)
            }
        } else if (state == ServerState.STOPPED) {
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                OpenCodeCoreService.getInstance(project).stopListening()
                OpenCodeTuiClient.getInstance(project).setPort(0)
            }
        }
    }

    val isRunning: Boolean get() = serverManager.isRunning
    val ownsProcess: Boolean get() = serverManager.ownsProcess

    // --- Resolved executable info ---

    @Volatile
    var executableResolutionState: OpenCodeExecutableResolutionState = OpenCodeExecutableResolutionState.Resolving
        private set

    val openCodeInfo: OpenCodeInfo?
        get() = (executableResolutionState as? OpenCodeExecutableResolutionState.Resolved)?.info

    /**
     * Runs [OpenCodeChecker.findExecutable] using the current settings path,
     * stores the result in [executableResolutionState], and publishes the change on the
     * project message bus via [OpenCodeInfoChangedListener.TOPIC].
     *
     * Safe to call from any thread; expensive resolution work is moved off the EDT,
     * while topic publication still happens on the EDT.
     */
    fun resolveExecutable() {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            application.executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                publishExecutableResolution(resolveExecutableState())
            }
            return
        }

        if (project.isDisposed) return
        publishExecutableResolution(resolveExecutableState())
    }

    fun setExecutableResolutionState(state: OpenCodeExecutableResolutionState) {
        publishExecutableResolution(state)
    }

    private fun resolveExecutableState(): OpenCodeExecutableResolutionState {
        val userPath = OpenCodeSettings.getInstance(project).executablePath.takeIf { it.isNotBlank() }
        val info = OpenCodeChecker.findExecutable(userPath)
        return info?.let(OpenCodeExecutableResolutionState::Resolved) ?: OpenCodeExecutableResolutionState.NotFound
    }

    private fun publishExecutableResolution(state: OpenCodeExecutableResolutionState) {
        if (state == executableResolutionState) return

        executableResolutionState = state
        val info = (state as? OpenCodeExecutableResolutionState.Resolved)?.info
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(OpenCodeInfoChangedListener.TOPIC)
                    .onOpenCodeInfoChanged(info)
            }
        }
    }

    @Volatile
    private var overrideState: ServerState? = null
    val serverState: ServerState get() = overrideState ?: serverManager.serverState

    private fun broadcastState(state: ServerState) {
        listeners.forEach { it.onStateChanged(state) }
    }

    fun startPolling(port: Int, intervalSeconds: Long = 10L) = serverManager.startPolling(port, intervalSeconds)
    fun checkPort(port: Int) = serverManager.checkPort(port)

    fun startServer(port: Int) {
        val resolvedExecutableInfo = openCodeInfo
        if (resolvedExecutableInfo == null) {
            log.warn("startServer() called but executable resolution is not in the resolved state")
            return
        }
        serverManager.startServer(port, resolvedExecutableInfo.path)
    }

    fun stopServer() = serverManager.stopServer()

    /**
     * Tears down the current core/TUI connections and re-attaches to the given [port].
     * Used when the user changes the port setting while attached to an external process
     * (i.e. we don't own the server process).
     */
    fun reattach(port: Int) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            OpenCodeCoreService.getInstance(project).stopListening()
            OpenCodeTuiClient.getInstance(project).setPort(0)
            startPolling(port)
            checkPort(port)
        }
    }

    fun resetConnection() {
        val port = OpenCodeSettings.getInstance(project).serverPort
        overrideState = ServerState.RESETTING
        broadcastState(ServerState.RESETTING)
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val diffService = OpenCodeCoreService.getInstance(project)
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
        private val log = logger<OpenCodePlugin>()

        fun getInstance(project: Project): OpenCodePlugin =
            project.getService(OpenCodePlugin::class.java)
    }
}
