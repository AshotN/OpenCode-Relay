package com.ashotn.opencode

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

enum class ServerState {
    /** Initial state — no check has completed yet. */
    UNKNOWN,
    /** We launched the process and are waiting for /global/health to return 200. */
    STARTING,
    /** /global/health returned 200 — server is fully ready. */
    READY,
    /** Port is closed — server is not running. */
    STOPPED,
    /** Port is open but occupied by a non-OpenCode process — user action required. */
    PORT_CONFLICT
}

fun interface ServerStateListener {
    fun onStateChanged(state: ServerState)
}

/**
 * Owns the OpenCode server process and all polling logic.
 *
 * Responsibilities:
 *  - Launch and kill the `opencode serve` process (and its process tree)
 *  - Register/remove a JVM shutdown hook so the process is cleaned up on ungraceful JVM exit
 *  - Poll the server port for liveness (TCP connect) and readiness (HTTP /global/health)
 *  - Drive [ServerState] transitions and notify [onStateChanged]
 */
class ServerManager(
    private val project: Project,
    private val onStateChanged: (ServerState) -> Unit,
) {

    companion object {
        private val log = logger<ServerManager>()

        private const val PORT_POLL_INTERVAL_SECONDS = 10L
        private const val PORT_POLL_INTERVAL_AFTER_START_SECONDS = 5L
        private const val HEALTH_POLL_INTERVAL_SECONDS = 2L
        private const val HEALTH_INITIAL_DELAY_SECONDS = 2L
        private const val HEALTH_CONNECT_TIMEOUT_MS = 1_000
        private const val HEALTH_READ_TIMEOUT_MS = 1_000
    }

    // --- State ---

    @Volatile var serverState: ServerState = ServerState.UNKNOWN
        private set

    val isRunning: Boolean
        get() = serverState == ServerState.READY || serverState == ServerState.STARTING

    @Volatile private var ownedProcess: Process? = null

    val ownsProcess: Boolean get() = ownedProcess != null

    @Volatile private var shutdownHook: Thread? = null

    /** Last-known port-open state for unexpected-stop detection. EDT-only. */
    private var wasPortOpen = false

    // --- Scheduler ---

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "opencode-poll").apply { isDaemon = true }
    }
    private var portPollFuture: ScheduledFuture<*>? = null
    private var healthPollFuture: ScheduledFuture<*>? = null

    // --- Port polling (liveness) ---

    fun startPolling(port: Int, intervalSeconds: Long = PORT_POLL_INTERVAL_SECONDS) {
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
        if (portOpen) {
            when {
                serverState == ServerState.READY -> {
                    wasPortOpen = true
                }
                ownedProcess != null -> {
                    wasPortOpen = true
                    if (serverState != ServerState.STARTING) applyState(ServerState.STARTING)
                    startHealthPolling(port)
                }
                else -> {
                    scheduler.submit { checkExternalHealth(port) }
                }
            }
            return
        }

        if (wasPortOpen) {
            notify("OpenCode stopped unexpectedly", "The OpenCode server process was terminated externally.", NotificationType.WARNING)
        }
        wasPortOpen = false
        stopHealthPolling()
        applyState(ServerState.STOPPED)
    }

    // --- Health polling (readiness) ---

    private fun startHealthPolling(port: Int, intervalSeconds: Long = HEALTH_POLL_INTERVAL_SECONDS) {
        if (healthPollFuture != null) return
        healthPollFuture = scheduler.scheduleWithFixedDelay(
            { doCheckHealth(port) },
            0L, intervalSeconds, TimeUnit.SECONDS
        )
    }

    private fun stopHealthPolling() {
        healthPollFuture?.cancel(false)
        healthPollFuture = null
    }

    private fun doCheckHealth(port: Int) {
        if (checkHealthOnce(port)) {
            SwingUtilities.invokeLater {
                stopHealthPolling()
                if (serverState == ServerState.STARTING) applyState(ServerState.READY)
            }
        }
    }

    private fun checkExternalHealth(port: Int) {
        val healthy = checkHealthOnce(port)
        SwingUtilities.invokeLater {
            if (healthy) {
                wasPortOpen = true
                applyState(ServerState.READY)
            } else {
                applyState(ServerState.PORT_CONFLICT)
            }
        }
    }

    private fun checkHealthOnce(port: Int): Boolean {
        val conn = URI("http://localhost:$port/global/health").toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = HEALTH_CONNECT_TIMEOUT_MS
            conn.readTimeout = HEALTH_READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

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
        onStateChanged(newState)
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
            // JVM is already shutting down — nothing to do.
        }
    }

    // --- Server lifecycle ---

    fun startServer(port: Int, executablePath: String) {
        scheduler.submit {
            val portAlreadyOpen = isPortOpen(port)

            if (portAlreadyOpen) {
                startPolling(port, intervalSeconds = PORT_POLL_INTERVAL_AFTER_START_SECONDS)
                SwingUtilities.invokeLater { applyPortResult(true, port) }
                return@submit
            }

            try {
                val process = ProcessBuilder(executablePath, "serve", "--port", port.toString())
                    .inheritIO()
                    .apply {
                        val basePath = project.basePath
                        if (basePath != null) directory(java.io.File(basePath))
                    }
                    .start()
                ownedProcess = process
                shutdownHook = registerShutdownHook(process)
                startPolling(port, intervalSeconds = PORT_POLL_INTERVAL_AFTER_START_SECONDS)
                process.onExit().thenRun {
                    if (ownedProcess === process) {
                        SwingUtilities.invokeLater {
                            ownedProcess = null
                            wasPortOpen = false
                            checkPort(port)
                        }
                    }
                }
                scheduler.schedule({ doCheckPort(port) }, HEALTH_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
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
        val process = ownedProcess ?: run {
            log.warn("stopServer() called but no process is owned")
            return
        }
        ownedProcess = null
        removeShutdownHook()
        wasPortOpen = false
        stopHealthPolling()
        applyState(ServerState.STOPPED)
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    // --- Dispose ---

    fun dispose() {
        stopPortPolling()
        stopHealthPolling()
        val process = ownedProcess
        if (process != null) {
            ownedProcess = null
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        scheduler.shutdownNow()
    }

    // --- Notifications ---

    private fun notify(title: String, content: String, type: NotificationType = NotificationType.ERROR) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeConstants.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
