package com.ashotn.opencode.companion

import com.ashotn.opencode.companion.core.OpenCodeCoreService
import com.ashotn.opencode.companion.settings.OpenCodeSettings
import com.ashotn.opencode.companion.tui.OpenCodeTuiClient
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
    private var executableResolutionCompleted: Boolean = false

    @Volatile
    var openCodeInfo: OpenCodeInfo? = null
        set(value) {
            field = value
            executableResolutionCompleted = true
        }

    val isExecutableResolutionCompleted: Boolean
        get() = executableResolutionCompleted

    /**
     * Runs [OpenCodeChecker.findExecutable] using the current settings path,
     * stores the result in [openCodeInfo], and publishes the change on the
     * project message bus via [OpenCodeInfoChangedListener.TOPIC].
     *
     * Safe to call from any thread; the topic is published on the EDT.
     */
    fun resolveExecutable() {
        val userPath = OpenCodeSettings.getInstance(project).executablePath.takeIf { it.isNotBlank() }
        val info = OpenCodeChecker.findExecutable(userPath)
        openCodeInfo = info
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
        val info = openCodeInfo
        if (info == null) {
            log.warn("startServer() called but openCodeInfo is null")
            return
        }
        serverManager.startServer(port, info.path)
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
