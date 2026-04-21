package com.ashotn.opencode.relay

import com.ashotn.opencode.relay.api.health.HealthApiClient
import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.processEnvironmentVariables
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

enum class ServerState {
    /** Initial state - no check has completed yet. */
    UNKNOWN,

    /** We launched the process and are waiting for /global/health to return 200. */
    STARTING,

    /** /global/health returned 200 - server is fully ready. */
    READY,

    /** Stop was requested and shutdown is in progress. */
    STOPPING,

    /** Port is closed - server is not running. */
    STOPPED,

    /** Port is open but occupied by a non-OpenCode process - user action required. */
    PORT_CONFLICT,

    /** Port is open and the process listening on it requires authentication. */
    AUTH_REQUIRED,

    /** A reset was requested - clearing state and reconnecting. */
    RESETTING,
}

fun interface ServerStateListener {
    fun onStateChanged(state: ServerState)
}

/**
 * Owns the OpenCode server process and all polling logic.
 */
class ServerManager(
    private val project: Project,
    private val onStateChanged: (ServerState) -> Unit,
) {

    private enum class HealthStatus {
        HEALTHY,
        AUTH_REQUIRED,
        UNHEALTHY,
    }

    companion object {
        private val log = logger<ServerManager>()

        private const val PORT_POLL_INTERVAL_SECONDS = 10L
        private const val PORT_POLL_INTERVAL_AFTER_START_SECONDS = 5L
        private const val HEALTH_POLL_INTERVAL_SECONDS = 2L
        private const val HEALTH_INITIAL_DELAY_SECONDS = 2L
    }

    @Volatile
    private var disposed = false

    @Volatile
    var serverState: ServerState = ServerState.UNKNOWN
        private set

    val isRunning: Boolean
        get() = serverState == ServerState.READY || serverState == ServerState.STARTING

    @Volatile
    private var ownedProcess: Process? = null

    val ownsProcess: Boolean get() = ownedProcess != null

    @Volatile
    private var shutdownHook: Thread? = null

    /** Last-known port-open state for unexpected-stop detection. EDT-only. */
    private var wasPortOpen = false

    /** Monotonic revision to ignore stale external health checks. */
    private val externalHealthRevision = AtomicLong(0)

    private val serverAuth = OpenCodeServerAuth.getInstance(project)
    private val healthApiClient = HealthApiClient(
        transport = OpenCodeHttpTransport(
            defaultConnectTimeoutMs = 1_000,
            defaultReadTimeoutMs = 1_000,
            authorizationHeaderProvider = serverAuth::connectionAuthorizationHeader,
        ),
    )

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "opencode-poll").apply { isDaemon = true }
    }
    private var portPollFuture: ScheduledFuture<*>? = null
    private var healthPollFuture: ScheduledFuture<*>? = null

    fun startPolling(port: Int, intervalSeconds: Long = PORT_POLL_INTERVAL_SECONDS) {
        portPollFuture?.cancel(false)
        portPollFuture = scheduler.scheduleWithFixedDelay(
            { checkPort(port) },
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS,
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
        if (serverState == ServerState.STOPPING) {
            if (!portOpen) {
                wasPortOpen = false
                stopHealthPolling()
                applyState(ServerState.STOPPED)
            } else {
                wasPortOpen = true
                externalHealthRevision.incrementAndGet()
            }
            return
        }

        if (portOpen) {
            when {
                serverState == ServerState.READY -> {
                    wasPortOpen = true
                    externalHealthRevision.incrementAndGet()
                }

                ownedProcess != null -> {
                    wasPortOpen = true
                    externalHealthRevision.incrementAndGet()
                    if (serverState != ServerState.STARTING) applyState(ServerState.STARTING)
                    startHealthPolling(port)
                }

                else -> {
                    val revision = externalHealthRevision.incrementAndGet()
                    scheduler.submit { checkExternalHealth(port, revision) }
                }
            }
            return
        }

        externalHealthRevision.incrementAndGet()
        if (wasPortOpen) {
            showNotification(
                "OpenCode stopped unexpectedly",
                "The OpenCode server process was terminated externally.",
                NotificationType.WARNING,
            )
        }
        wasPortOpen = false
        stopHealthPolling()
        applyState(ServerState.STOPPED)
    }

    private fun startHealthPolling(port: Int, intervalSeconds: Long = HEALTH_POLL_INTERVAL_SECONDS) {
        if (healthPollFuture != null) return
        healthPollFuture = scheduler.scheduleWithFixedDelay(
            { doCheckHealth(port) },
            0L,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    private fun stopHealthPolling() {
        healthPollFuture?.cancel(false)
        healthPollFuture = null
    }

    private fun doCheckHealth(port: Int) {
        when (checkHealthStatus(port)) {
            HealthStatus.HEALTHY -> {
                SwingUtilities.invokeLater {
                    stopHealthPolling()
                    if (serverState == ServerState.STARTING) applyState(ServerState.READY)
                }
            }

            HealthStatus.AUTH_REQUIRED -> {
                SwingUtilities.invokeLater {
                    wasPortOpen = true
                    stopHealthPolling()
                    applyState(ServerState.AUTH_REQUIRED)
                }
            }

            HealthStatus.UNHEALTHY -> Unit
        }
    }

    private fun checkExternalHealth(port: Int, revision: Long) {
        val healthStatus = checkHealthStatus(port)
        SwingUtilities.invokeLater {
            if (revision != externalHealthRevision.get()) return@invokeLater
            if (ownedProcess != null || serverState == ServerState.STARTING) return@invokeLater

            when (healthStatus) {
                HealthStatus.HEALTHY -> {
                    wasPortOpen = true
                    applyState(ServerState.READY)
                }

                HealthStatus.AUTH_REQUIRED -> {
                    wasPortOpen = true
                    applyState(ServerState.AUTH_REQUIRED)
                }

                HealthStatus.UNHEALTHY -> {
                    applyState(ServerState.PORT_CONFLICT)
                }
            }
        }
    }

    private fun checkHealthStatus(port: Int): HealthStatus =
        try {
            when (val result = healthApiClient.isHealthy(port)) {
                is ApiResult.Success -> if (result.value) HealthStatus.HEALTHY else HealthStatus.UNHEALTHY
                is ApiResult.Failure -> {
                    log.debug("ServerManager: health check failed for port $port reason=${result.error}")
                    if (result.error.isAuthenticationFailure()) HealthStatus.AUTH_REQUIRED else HealthStatus.UNHEALTHY
                }
            }
        } catch (e: Exception) {
            log.debug("ServerManager: health check failed for port $port", e)
            HealthStatus.UNHEALTHY
        }

    private fun ApiError.isAuthenticationFailure(): Boolean =
        this is ApiError.HttpError && (statusCode == 401 || statusCode == 403)

    private fun showNotification(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(OpenCodeConstants.NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    fun reportAuthenticationRequired() {
        SwingUtilities.invokeLater {
            wasPortOpen = true
            stopHealthPolling()
            applyState(ServerState.AUTH_REQUIRED)
        }
    }

    private fun isPortOpen(port: Int): Boolean {
        val addresses = try {
            InetAddress.getAllByName("localhost").toList()
        } catch (e: Exception) {
            log.debug("ServerManager: failed to resolve localhost, using loopback", e)
            listOf(InetAddress.getLoopbackAddress())
        }

        return addresses.any { addr ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(addr, port), 500)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun applyState(newState: ServerState) {
        val oldState = serverState
        if (oldState == newState) return
        serverState = newState
        log.info("ServerManager: state $oldState -> $newState")
        onStateChanged(newState)
    }

    private fun registerShutdownHook(process: Process): Thread {
        val hook = Thread(
            Thread.currentThread().threadGroup,
            {
                process.toHandle().descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            },
            "opencode-shutdown-hook",
        )
        Runtime.getRuntime().addShutdownHook(hook)
        return hook
    }

    private fun removeShutdownHook() {
        val hook = shutdownHook ?: return
        shutdownHook = null
        try {
            Runtime.getRuntime().removeShutdownHook(hook)
        } catch (_: IllegalStateException) {
            // JVM is already shutting down - nothing to do.
        }
    }

    private fun buildWindowsCommand(executablePath: String, vararg args: String): String =
        buildString {
            append("\"")
            append(executablePath)
            append("\"")
            args.forEach {
                append(' ')
                append("\"")
                append(it)
                append("\"")
            }
        }

    internal fun buildServeArguments(settings: OpenCodeSettings, port: Int): List<String> {
        val hostname = settings.serverHostname.trim()
        val mdnsDomain = settings.serverMdnsDomain.trim()
        return buildList {
            add("serve")
            add("--port")
            add(port.toString())
            if (hostname.isNotEmpty()) {
                add("--hostname")
                add(hostname)
            }
            if (settings.serverMdnsEnabled) {
                add("--mdns")
                if (mdnsDomain.isNotEmpty()) {
                    add("--mdns-domain")
                    add(mdnsDomain)
                }
            }
            settings.serverCorsOrigins
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { origin ->
                    add("--cors")
                    add(origin)
                }
        }
    }

    fun startServer(port: Int, executablePath: String) {
        val executable = File(executablePath)
        val isLaunchable = if (SystemInfo.isWindows) {
            executable.exists() && executable.isFile
        } else {
            executable.isFile && executable.canExecute()
        }
        if (!isLaunchable) {
            showNotification(
                "Failed to start OpenCode server",
                "The OpenCode executable is not valid or executable: $executablePath",
                NotificationType.ERROR,
            )
            applyState(ServerState.STOPPED)
            return
        }

        scheduler.submit {
            val portAlreadyOpen = isPortOpen(port)

            if (portAlreadyOpen) {
                startPolling(port, intervalSeconds = PORT_POLL_INTERVAL_AFTER_START_SECONDS)
                SwingUtilities.invokeLater { applyPortResult(true, port) }
                return@submit
            }

            try {
                val settings = OpenCodeSettings.getInstance(project)
                val environmentVariables =
                    settings.processEnvironmentVariables(serverAuth.serverLaunchEnvironmentVariables())
                val serveArguments = buildServeArguments(settings, port)
                val command =
                    if (SystemInfo.isWindows) {
                        listOf("cmd", "/c", buildWindowsCommand(executablePath, *serveArguments.toTypedArray()))
                    } else {
                        listOf(executablePath) + serveArguments
                    }
                val process = ProcessBuilder(command)
                    .inheritIO()
                    .apply {
                        OpenCodeProcessEnvironment.configure(this, executablePath, environmentVariables)
                        val basePath = project.basePath
                        if (basePath != null) directory(File(basePath))
                    }
                    .start()

                ownedProcess = process
                shutdownHook = registerShutdownHook(process)
                startPolling(port, intervalSeconds = PORT_POLL_INTERVAL_AFTER_START_SECONDS)

                process.onExit().thenRun {
                    if (!disposed && ownedProcess === process) {
                        SwingUtilities.invokeLater {
                            if (!disposed) {
                                removeShutdownHook()
                                ownedProcess = null
                                wasPortOpen = false
                                checkPort(port)
                            }
                        }
                    }
                }

                scheduler.schedule({ doCheckPort(port) }, HEALTH_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    when (e) {
                        is OpenCodeServerAuth.MissingServerLaunchAuthCredentialsException -> {
                            showNotification(
                                "Failed to start OpenCode server",
                                "Server authentication is enabled, but the password is missing. Re-enter it in Settings | OpenCode Relay.",
                                NotificationType.ERROR,
                            )
                        }

                        else -> {
                            val message = e.message ?: e.javaClass.simpleName
                            showNotification(
                                "Failed to start OpenCode server",
                                "Could not launch the OpenCode server process: $message",
                                NotificationType.ERROR,
                            )
                        }
                    }
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

        applyState(ServerState.STOPPING)
        ownedProcess = null
        removeShutdownHook()
        wasPortOpen = false
        externalHealthRevision.incrementAndGet()
        stopHealthPolling()

        scheduler.submit {
            forceKillProcess(process)
            SwingUtilities.invokeLater {
                applyState(ServerState.STOPPED)
            }
        }
    }

    private fun forceKillProcess(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    fun dispose() {
        disposed = true
        stopPortPolling()
        stopHealthPolling()
        externalHealthRevision.incrementAndGet()
        removeShutdownHook()

        val process = ownedProcess
        if (process != null) {
            ownedProcess = null
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        scheduler.shutdownNow()
    }

}
