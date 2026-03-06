package com.ashotn.opencode

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

@Service(Service.Level.PROJECT)
class OpenCodePlugin(private val project: Project) : Disposable {

    enum class ServerState {
        /** Initial state — no check has completed yet. */
        UNKNOWN,
        /** We launched the process; port is open but /global/health has not returned 200 yet. */
        STARTING,
        /** /global/health returned 200 — server is fully ready. */
        READY,
        /** Port is closed — server is not running. */
        STOPPED,
        /** Port is open but occupied by a non-OpenCode process — user action required. */
        PORT_CONFLICT
    }

    interface ServerStateListener {
        fun onStateChanged(state: ServerState)
    }

    // --- State (EDT-only after initial write) ---

    @Volatile var serverState: ServerState = ServerState.UNKNOWN
        private set

    /** Convenience for callers that only care about running vs. not. */
    val isRunning: Boolean get() = serverState == ServerState.READY || serverState == ServerState.STARTING

    /** Non-null only when we launched the process ourselves. */
    @Volatile private var ownedProcess: Process? = null

    /** True when we launched and currently own the server process. */
    val ownsProcess: Boolean get() = ownedProcess != null

    /**
     * JVM shutdown hook that kills the owned process tree on ungraceful JVM exit (e.g. SIGTERM,
     * Ctrl+C). Registered when we launch a server; removed when the user stops the server
     * intentionally via stopServer() so it doesn't fire redundantly against an already-dead process.
     *
     * Note: this does NOT run on SIGKILL — nothing in the JVM can intercept that.
     */
    @Volatile private var shutdownHook: Thread? = null

    /** Last-known port-open state for unexpected-stop detection. EDT-only. */
    private var wasPortOpen = false

    // --- Listeners ---

    private val listeners = CopyOnWriteArrayList<ServerStateListener>()

    fun addListener(listener: ServerStateListener) = listeners.add(listener)
    fun removeListener(listener: ServerStateListener) = listeners.remove(listener)

    // --- Scheduler ---

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "opencode-poll").apply { isDaemon = true }
    }
    private var portPollFuture: ScheduledFuture<*>? = null
    private var healthPollFuture: ScheduledFuture<*>? = null

    // --- Port polling (liveness) ---

    fun startPolling(port: Int, intervalSeconds: Long = 10L) {
        portPollFuture?.cancel(false)
        portPollFuture = scheduler.scheduleWithFixedDelay(
            { checkPort(port) },
            intervalSeconds, intervalSeconds, TimeUnit.SECONDS
        )
    }

    private fun stopPortPolling() {
        portPollFuture?.cancel(false)
        portPollFuture = null
    }

    fun checkPort(port: Int) {
        scheduler.submit { doCheckPort(port) }
    }

    private fun doCheckPort(port: Int) {
        SwingUtilities.invokeLater { applyPortResult(isPortOpen(port), port) }
    }

    private fun applyPortResult(portOpen: Boolean, port: Int) {
        if (!portOpen) {
            // Port closed — notify only if we were tracking an externally-discovered OpenCode
            // instance that vanished without us stopping it. Processes we own are accounted for
            // by the onExit() handler; wasPortOpen is only true for confirmed-READY external servers.
            if (wasPortOpen) {
                notify("OpenCode stopped unexpectedly", "The OpenCode server process was terminated externally.", NotificationType.WARNING)
            }
            wasPortOpen = false
            stopHealthPolling()
            applyState(ServerState.STOPPED)
        } else {
            when {
                serverState == ServerState.READY -> {
                    // Already confirmed as ours — nothing to do.
                    wasPortOpen = true
                }
                ownedProcess != null -> {
                    // We launched this process — poll health until it becomes ready.
                    wasPortOpen = true
                    if (serverState != ServerState.STARTING) applyState(ServerState.STARTING)
                    startHealthPolling(port)
                }
                else -> {
                    // Port is open but we didn't launch it. Do one immediate health check:
                    // if it's OpenCode → READY (and we'll set wasPortOpen then), otherwise
                    // → PORT_CONFLICT. Leave wasPortOpen false so that when the conflicting
                    // process later exits, we don't fire "stopped unexpectedly".
                    scheduler.submit { checkExternalHealth(port) }
                }
            }
        }
    }

    // --- Health polling (readiness) ---

    private fun startHealthPolling(port: Int, intervalSeconds: Long = 2L) {
        if (healthPollFuture != null) return  // already polling
        healthPollFuture = scheduler.scheduleWithFixedDelay(
            { doCheckHealth(port) },
            0L, intervalSeconds, TimeUnit.SECONDS
        )
    }

    private fun stopHealthPolling() {
        healthPollFuture?.cancel(false)
        healthPollFuture = null
    }

    /** Single-shot health check for a process we own — called repeatedly by health polling. */
    private fun doCheckHealth(port: Int) {
        if (checkHealthOnce(port)) {
            SwingUtilities.invokeLater {
                stopHealthPolling()
                if (serverState == ServerState.STARTING) applyState(ServerState.READY)
            }
        }
        // Not healthy yet — keep polling. The port poll handles the case where the process exits.
    }

    /**
     * Single-shot health check for a port we discovered externally (no owned process).
     * Resolves immediately to READY or PORT_CONFLICT without any polling loop.
     */
    private fun checkExternalHealth(port: Int) {
        val healthy = checkHealthOnce(port)
        SwingUtilities.invokeLater {
            if (healthy) {
                wasPortOpen = true
                applyState(ServerState.READY)
            } else {
                // Port is taken by a non-OpenCode process. wasPortOpen stays false so the
                // "stopped unexpectedly" guard doesn't trigger when that process later exits.
                applyState(ServerState.PORT_CONFLICT)
            }
        }
    }

    /** Returns true if /global/health responds with HTTP 200. */
    private fun checkHealthOnce(port: Int): Boolean = try {
        val conn = URI("http://localhost:$port/global/health").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 1_000
        conn.readTimeout = 1_000
        conn.requestMethod = "GET"
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (_: Exception) {
        false
    }

    /**
     * Returns true if anything is listening on [port] on any local interface.
     *
     * A plain `Socket("localhost", port)` only probes whichever address the JVM resolves
     * "localhost" to first — typically 127.0.0.1 (IPv4). This misses processes that bind
     * exclusively to ::1 (IPv6 loopback). We resolve all addresses for "localhost" and try
     * each one, so IPv4-only, IPv6-only, and dual-stack listeners are all detected.
     */
    private fun isPortOpen(port: Int): Boolean {
        val addresses = try {
            InetAddress.getAllByName("localhost").toList()
        } catch (_: Exception) {
            listOf(InetAddress.getLoopbackAddress())
        }
        return addresses.any { addr ->
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(addr, port), 500)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun applyState(newState: ServerState) {
        serverState = newState
        listeners.forEach { it.onStateChanged(newState) }
    }

    // --- Shutdown hook helpers ---

    private fun registerShutdownHook(process: Process): Thread {
        val hook = Thread(Thread.currentThread().threadGroup, {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }, "opencode-shutdown-hook")
        Runtime.getRuntime().addShutdownHook(hook)
        return hook
    }

    private fun removeShutdownHook() {
        val hook = shutdownHook ?: return
        shutdownHook = null
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // JVM is already shutting down — the hook will run (or has run); nothing to do.
        }
    }

    // --- Server lifecycle ---

    fun startServer(port: Int, executablePath: String) {
        scheduler.submit {
            // Pre-flight: check whether the port is already occupied before we try to bind.
            // This covers the race where the user clicks Start just before the UI updates to
            // show an externally-running server, and the general "port taken" case.
            // If the port is already open, skip launching and hand off to the polling path —
            // it will discover the running server and transition to STARTING → READY normally.
            val portAlreadyOpen = isPortOpen(port)

            if (portAlreadyOpen) {
                // Something is already listening — treat it the same as discovering an
                // external server: start polling and let the state machine sort it out.
                startPolling(port, intervalSeconds = 5L)
                SwingUtilities.invokeLater { applyPortResult(true, port) }
                return@submit
            }

            try {
                val process = ProcessBuilder(executablePath, "serve", "--port", port.toString())
                    .inheritIO()
                    .start()
                ownedProcess = process
                shutdownHook = registerShutdownHook(process)
                // Poll frequently while starting, slow down once we know it's up
                startPolling(port, intervalSeconds = 5L)
                process.onExit().thenRun {
                    // Only handle unexpected exits here. Intentional stops are handled
                    // synchronously in stopServer() before this callback fires.
                    if (ownedProcess === process) {
                        SwingUtilities.invokeLater {
                            ownedProcess = null
                            wasPortOpen = false
                            checkPort(port)
                        }
                    }
                }
                scheduler.schedule({ doCheckPort(port) }, 2L, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // The process could not be launched (e.g. executable not found or permission denied).
                // Immediately revert to STOPPED so the UI doesn't get stuck in "Starting…".
                val message = e.message ?: e.javaClass.simpleName
                SwingUtilities.invokeLater {
                    notify(
                        "Failed to start OpenCode",
                        "Could not launch the OpenCode process: $message",
                        NotificationType.ERROR
                    )
                    applyState(ServerState.STOPPED)
                }
            }
        }
    }

    fun stopServer() {
        // Transition state immediately on the EDT — we know the process is gone because we
        // killed it, so there is no need to wait for a port check to confirm.
        // Nulling ownedProcess before destroyForcibly() prevents the onExit() callback from
        // treating this as an unexpected exit and issuing a redundant checkPort.
        val process = ownedProcess ?: error("stopServer() called but no process is owned")
        ownedProcess = null
        removeShutdownHook()
        wasPortOpen = false
        stopHealthPolling()
        applyState(ServerState.STOPPED)
        // Kill the entire process tree. opencode is a Node.js wrapper that uses spawnSync to
        // launch the real native binary as a child, so destroying only the direct process
        // handle leaves the grandchild running and holding the port.
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    // --- Notifications ---

    private fun notify(title: String, content: String, type: NotificationType = NotificationType.ERROR) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("OpenCode")
            .createNotification(title, content, type)
            .notify(project)
    }

    // --- Disposable ---

    override fun dispose() {
        stopPortPolling()
        stopHealthPolling()
        // Kill the owned server process (and its entire process tree) so it doesn't
        // become an orphan when the IDE closes or the project is unloaded.
        val process = ownedProcess
        if (process != null) {
            ownedProcess = null
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        scheduler.shutdownNow()
    }

    companion object {
        fun getInstance(project: Project): OpenCodePlugin =
            project.getService(OpenCodePlugin::class.java)
    }
}
