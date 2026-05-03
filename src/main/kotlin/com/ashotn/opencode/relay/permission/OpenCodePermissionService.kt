package com.ashotn.opencode.relay.permission

import com.ashotn.opencode.relay.api.permission.PermissionApiClient
import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.isAuthenticationFailure
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.PermissionChangedListener
import com.ashotn.opencode.relay.ipc.PermissionReply
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.relay.util.showNotification
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AppIcon
import java.util.concurrent.ConcurrentLinkedQueue

@Service(Service.Level.PROJECT)
class OpenCodePermissionService(private val project: Project) : Disposable {

    companion object {
        fun getInstance(project: Project): OpenCodePermissionService =
            project.getService(OpenCodePermissionService::class.java)
    }

    private val log = logger<OpenCodePermissionService>()
    private val permissionApiClient = PermissionApiClient(
        OpenCodeHttpTransport(
            authorizationHeaderProvider = OpenCodeServerAuth.getInstance(project)::connectionAuthorizationHeader,
        ),
    )

    @Volatile
    private var port: Int = 0

    private val permissionLock = Any()
    private val permissionQueue = ConcurrentLinkedQueue<OpenCodeEvent.PermissionAsked>()
    private var permissionAlertGeneration = 0L
    private var braveModeEnabled = OpenCodeSettings.getInstance(project).braveModeEnabled

    init {
        project.messageBus.connect(this).subscribe(
            OpenCodeSettingsChangedListener.TOPIC,
            OpenCodeSettingsChangedListener { oldSettings, newSettings ->
                if (!oldSettings.braveModeEnabled && newSettings.braveModeEnabled) {
                    autoAcceptPendingPermissions()
                } else {
                    synchronized(permissionLock) {
                        braveModeEnabled = newSettings.braveModeEnabled
                    }
                }
            },
        )
    }

    fun setPort(port: Int) {
        this.port = port
    }

    fun currentPermission(): OpenCodeEvent.PermissionAsked? = synchronized(permissionLock) {
        if (braveModeEnabled) return@synchronized null
        permissionQueue.peek()
    }

    fun clearPermissions() {
        val hadPermissions = synchronized(permissionLock) {
            val hadPermissions = permissionQueue.isNotEmpty()
            permissionQueue.clear()
            hadPermissions
        }
        if (hadPermissions) publishPermissionChanged()
    }

    fun handlePermissionAsked(event: OpenCodeEvent.PermissionAsked) {
        log.info("OpenCodePermissionService: permission.asked id=${event.requestId} permission=${event.permission}")
        val (shouldAutoAccept, alertGeneration) = synchronized(permissionLock) {
            if (braveModeEnabled) {
                return@synchronized true to null
            }

            val wasEmpty = permissionQueue.isEmpty()
            permissionQueue.add(event)
            false to if (wasEmpty) ++permissionAlertGeneration else null
        }

        if (shouldAutoAccept) {
            sendPermissionReply(event, PermissionReply.ONCE)
            return
        }

        if (alertGeneration != null) {
            publishPermissionChanged()
            ApplicationManager.getApplication().invokeLater {
                val current = currentPermissionForAlert(alertGeneration)
                if (!project.isDisposed && current != null) alertPermissionRequested(current)
            }
        }
    }

    fun handlePermissionReplied(event: OpenCodeEvent.PermissionReplied) {
        log.info("OpenCodePermissionService: permission.replied id=${event.requestId} reply=${event.reply.wireValue}")
        val wasHead = synchronized(permissionLock) {
            val wasHead = permissionQueue.peek()?.requestId == event.requestId
            permissionQueue.removeIf { it.requestId == event.requestId }
            wasHead
        }
        if (wasHead) publishPermissionChanged()
    }

    fun replyToPermission(response: PermissionReply) {
        val event = currentPermission() ?: return
        sendPermissionReply(event, response)
    }

    private fun autoAcceptPendingPermissions() {
        val events = synchronized(permissionLock) {
            braveModeEnabled = true
            if (permissionQueue.isEmpty()) return
            permissionAlertGeneration++
            permissionQueue.toList().also { permissionQueue.clear() }
        }
        publishPermissionChanged()
        events.forEach { event -> sendPermissionReply(event, PermissionReply.ONCE) }
    }

    private fun sendPermissionReply(event: OpenCodeEvent.PermissionAsked, response: PermissionReply) {
        val currentPort = port

        if (currentPort <= 0) {
            log.warn("OpenCodePermissionService: cannot reply to permission ${event.requestId}, invalid port=$currentPort")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            when (
                val result = permissionApiClient.reply(
                    port = currentPort,
                    sessionId = event.sessionId,
                    permissionId = event.requestId,
                    response = response.wireValue,
                )
            ) {
                is ApiResult.Success -> {
                    if (result.value) {
                        log.info("OpenCodePermissionService: permission reply ${response.wireValue} accepted")
                    } else {
                        log.warn("OpenCodePermissionService: permission reply ${response.wireValue} rejected")
                    }
                }

                is ApiResult.Failure -> {
                    log.warn(
                        "OpenCodePermissionService: permission reply ${response.wireValue} failed error=${
                            apiErrorMessage(
                                result.error
                            )
                        }",
                    )
                }
            }
        }
    }

    private fun apiErrorMessage(error: ApiError): String = when (error) {
        is ApiError.HttpError -> if (error.isAuthenticationFailure()) {
            "OpenCode authentication failed. Update Server Authentication settings."
        } else {
            "HTTP ${error.statusCode}"
        }

        is ApiError.NetworkError -> error.message
        is ApiError.ParseError -> error.message
    }

    private fun publishPermissionChanged() {
        project.messageBus
            .syncPublisher(PermissionChangedListener.TOPIC)
            .onPermissionChanged(currentPermission())
    }

    private fun currentPermissionForAlert(alertGeneration: Long): OpenCodeEvent.PermissionAsked? =
        synchronized(permissionLock) {
            if (!braveModeEnabled && permissionAlertGeneration == alertGeneration) permissionQueue.peek() else null
        }

    private fun alertPermissionRequested(event: OpenCodeEvent.PermissionAsked) {
        if (!isProjectFrameActive()) {
            AppIcon.getInstance().requestAttention(project, true)
        }

        if (!isPermissionUiVisible()) {
            project.showNotification(
                "OpenCode needs permission",
                "Review the pending ${event.permission} permission request.",
                NotificationType.WARNING,
            )
        }
    }

    private fun isProjectFrameActive(): Boolean =
        WindowManager.getInstance().getFrame(project)?.isActive == true

    private fun isPermissionUiVisible(): Boolean {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenCode Relay")
        if (toolWindow?.isVisible != true) return false

        val settings = OpenCodeSettings.getInstance(project)
        return !settings.inlineTerminalEnabled || settings.sessionsSectionVisible
    }

    override fun dispose() {
        synchronized(permissionLock) {
            permissionQueue.clear()
        }
    }
}
