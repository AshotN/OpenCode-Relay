package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.api.service.OpenCodeBackgroundService
import com.ashotn.opencode.relay.api.service.OpenCodeBackgroundServiceDetector
import com.ashotn.opencode.relay.core.OpenCodeCoreService
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.tui.OpenCodeTuiClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@Service(Service.Level.PROJECT)
class OpenCodePlugin(private val project: Project) : Disposable {

    @Volatile
    private var disposed = false

    private fun isInactive(): Boolean = disposed || project.isDisposed

    // --- Listeners ---

    private val listeners = CopyOnWriteArrayList<ServerStateListener>()
    private val connectionSyncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "opencode-connection-sync").apply { isDaemon = true }
    }

    fun addListener(listener: ServerStateListener) = listeners.add(listener)
    fun removeListener(listener: ServerStateListener) = listeners.remove(listener)

    // --- ServerManager delegation ---

    private val serverManager = ServerManager(project) { state ->
        listeners.forEach { it.onStateChanged(state) }
        scheduleConnectionSync(state)
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
    fun resolveExecutable(onResolved: ((OpenCodeExecutableResolutionState) -> Unit)? = null) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            application.executeOnPooledThread {
                resolveExecutableAndNotify(onResolved)
            }
            return
        }

        resolveExecutableAndNotify(onResolved)
    }

    private fun resolveExecutableAndNotify(onResolved: ((OpenCodeExecutableResolutionState) -> Unit)?) {
        if (isInactive()) return
        val state = resolveExecutableState()
        publishExecutableResolution(state)
        if (!isInactive()) {
            onResolved?.invoke(state)
        }
    }

    fun setExecutableResolutionState(state: OpenCodeExecutableResolutionState) {
        publishExecutableResolution(state)
    }

    private fun resolveExecutableState(): OpenCodeExecutableResolutionState {
        val userPath = OpenCodeSettings.getInstance(project).executablePath.takeIf { it.isNotBlank() }
        return when (val detection = OpenCodeChecker.findExecutable(userPath)) {
            is OpenCodeExecutableDetection.Found -> OpenCodeExecutableResolutionState.Resolved(detection.info)
            is OpenCodeExecutableDetection.IncompatibleVersion -> OpenCodeExecutableResolutionState.IncompatibleVersion(detection.info)
            OpenCodeExecutableDetection.None -> OpenCodeExecutableResolutionState.NotFound
        }
    }

    private fun publishExecutableResolution(state: OpenCodeExecutableResolutionState) {
        if (state == executableResolutionState) return

        executableResolutionState = state
        val info = (state as? OpenCodeExecutableResolutionState.Resolved)?.info
        ApplicationManager.getApplication().invokeLater {
            if (!isInactive()) {
                project.messageBus.syncPublisher(OpenCodeInfoChangedListener.TOPIC)
                    .onOpenCodeInfoChanged(info)
            }
        }
    }

    @Volatile
    private var detectedService: OpenCodeBackgroundService? = null

    /**
     * The port used for all API connections: the detected background service's
     * port when one is running, otherwise the configured server port.
     */
    val connectionPort: Int
        get() = detectedService?.port ?: OpenCodeSettings.getInstance(project).serverPort

    /**
     * Looks for a running OpenCode 2.x background service (started via
     * `opencode service start`). When one is found, its password is loaded into
     * [OpenCodeServerAuth] and the plugin connects to its port.
     *
     * Detection runs at most once per plugin lifetime; [onDetected] is invoked
     * with the detected service (or null) once the outcome is known.
     */
    fun detectBackgroundService(onDetected: ((OpenCodeBackgroundService?) -> Unit)? = null) {
        detectedService?.let { service ->
            onDetected?.invoke(service)
            return
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            application.executeOnPooledThread { detectBackgroundServiceInternal(onDetected) }
            return
        }
        detectBackgroundServiceInternal(onDetected)
    }

    private fun detectBackgroundServiceInternal(onDetected: ((OpenCodeBackgroundService?) -> Unit)?) {
        if (isInactive()) return

        val service = runCatching { OpenCodeBackgroundServiceDetector().detect() }
            .onFailure { log.warn("Background service detection failed", it) }
            .getOrNull()
        if (isInactive()) return

        if (service != null) {
            OpenCodeServerAuth.getInstance(project).setPasswordTransiently(service.password)
            detectedService = service
            log.info("Detected OpenCode background service at ${service.url}")
            startPolling(service.port)
            checkPort(service.port)
        }
        onDetected?.invoke(service)
    }

    @Volatile
    private var overrideState: ServerState? = null
    val serverState: ServerState get() = overrideState ?: serverManager.serverState

    private fun broadcastState(state: ServerState) {
        listeners.forEach { it.onStateChanged(state) }
    }

    private fun attachRelayConnections(port: Int) {
        if (isInactive()) return

        val diffService = OpenCodeCoreService.getInstance(project)
        val tuiClient = OpenCodeTuiClient.getInstance(project)
        diffService.startListening(port)
        if (isInactive()) {
            diffService.stopListening()
            return
        }
        tuiClient.setPort(port)
    }

    private fun detachRelayConnections() {
        if (isInactive()) return

        OpenCodeCoreService.getInstance(project).stopListening()
        OpenCodeTuiClient.getInstance(project).setPort(0)
    }

    private fun scheduleConnectionSync(state: ServerState) {
        submitConnectionSyncTask {
            when (state) {
                ServerState.READY -> {
                    attachRelayConnections(connectionPort)
                }

                ServerState.STOPPED -> {
                    if (!serverManager.ownsProcess) {
                        // The external target we were connected to is gone; fall
                        // back to the configured port for the next connection.
                        detectedService = null
                    }
                    detachRelayConnections()
                    retargetPolling()
                }

                ServerState.PORT_CONFLICT, ServerState.AUTH_REQUIRED -> {
                    detachRelayConnections()
                }

                else -> Unit
            }
        }
    }

    /** Re-points port polling at the current [connectionPort]. */
    private fun retargetPolling() {
        val port = connectionPort
        startPolling(port)
        checkPort(port)
    }

    fun startPolling(port: Int, intervalSeconds: Long = 10L) = serverManager.startPolling(port, intervalSeconds)
    fun checkPort(port: Int) = serverManager.checkPort(port)

    fun startServer(port: Int) {
        val resolvedExecutableInfo = openCodeInfo
        if (resolvedExecutableInfo == null) {
            log.warn("startServer() called but executable resolution is not in the resolved state")
            return
        }
        // We are about to own the server process, so a previously detected
        // background service is no longer the connection target.
        detectedService = null
        serverManager.startServer(port, resolvedExecutableInfo.path)
    }

    fun stopServer() = serverManager.stopServer()

    /**
     * Tears down the current core/TUI connections and re-attaches to the current
     * [connectionPort]. Used when the user changes the port setting while attached
     * to an external process (i.e. we don't own the server process).
     */
    fun reattach() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isInactive()) return@executeOnPooledThread
            detachRelayConnections()
            if (isInactive()) return@executeOnPooledThread
            retargetPolling()
        }
    }

    fun resetConnection() {
        val port = connectionPort
        overrideState = ServerState.RESETTING
        broadcastState(ServerState.RESETTING)
        val submitted = submitConnectionSyncTask {
            try {
                detachRelayConnections()
                attachRelayConnections(port)
            } finally {
                overrideState = null
                ApplicationManager.getApplication().invokeLater {
                    if (!isInactive()) {
                        broadcastState(serverManager.serverState)
                    }
                }
            }
        }

        if (!submitted) {
            overrideState = null
            if (!isInactive()) {
                broadcastState(serverManager.serverState)
            }
        }
    }

    private fun submitConnectionSyncTask(task: () -> Unit): Boolean {
        if (isInactive()) return false

        return try {
            connectionSyncExecutor.execute {
                if (isInactive()) return@execute
                task()
            }
            true
        } catch (e: RejectedExecutionException) {
            if (!isInactive()) {
                log.warn("Connection sync task was rejected before plugin disposal completed", e)
            }
            false
        }
    }

    fun reportAuthenticationRequired() {
        serverManager.reportAuthenticationRequired()
    }

    // --- Disposable ---

    override fun dispose() {
        disposed = true
        listeners.clear()
        serverManager.dispose()
        connectionSyncExecutor.shutdownNow()
    }

    companion object {
        private val log = logger<OpenCodePlugin>()

        fun getInstance(project: Project): OpenCodePlugin =
            project.getService(OpenCodePlugin::class.java)
    }
}
