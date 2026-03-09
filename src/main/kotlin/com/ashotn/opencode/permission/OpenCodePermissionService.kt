package com.ashotn.opencode.permission

import com.ashotn.opencode.api.permission.PermissionApiClient
import com.ashotn.opencode.api.transport.ApiError
import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.PermissionChangedListener
import com.ashotn.opencode.ipc.PermissionReply
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentLinkedQueue

@Service(Service.Level.PROJECT)
class OpenCodePermissionService(private val project: Project) : Disposable {

    companion object {
        fun getInstance(project: Project): OpenCodePermissionService =
            project.getService(OpenCodePermissionService::class.java)
    }

    private val log = logger<OpenCodePermissionService>()
    private val permissionApiClient = PermissionApiClient()

    @Volatile private var port: Int = 0

    private val permissionQueue = ConcurrentLinkedQueue<OpenCodeEvent.PermissionAsked>()

    fun setPort(port: Int) {
        this.port = port
    }

    fun currentPermission(): OpenCodeEvent.PermissionAsked? = permissionQueue.peek()

    fun clearPermissions() {
        val hadPermissions = permissionQueue.isNotEmpty()
        permissionQueue.clear()
        if (hadPermissions) publishPermissionChanged()
    }

    fun handlePermissionAsked(event: OpenCodeEvent.PermissionAsked) {
        log.info("OpenCodePermissionService: permission.asked id=${event.requestId} permission=${event.permission}")
        val wasEmpty = permissionQueue.isEmpty()
        permissionQueue.add(event)
        if (wasEmpty) publishPermissionChanged()
    }

    fun handlePermissionReplied(event: OpenCodeEvent.PermissionReplied) {
        log.info("OpenCodePermissionService: permission.replied id=${event.requestId} reply=${event.reply.wireValue}")
        val wasHead = permissionQueue.peek()?.requestId == event.requestId
        permissionQueue.removeIf { it.requestId == event.requestId }
        if (wasHead) publishPermissionChanged()
    }

    fun replyToPermission(response: PermissionReply) {
        val event = permissionQueue.peek() ?: return
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
                        "OpenCodePermissionService: permission reply ${response.wireValue} failed error=${apiErrorMessage(result.error)}",
                    )
                }
            }
        }
    }

    private fun apiErrorMessage(error: ApiError): String = when (error) {
        is ApiError.HttpError -> "HTTP ${error.statusCode}"
        is ApiError.NetworkError -> error.message
        is ApiError.ParseError -> error.message
    }

    private fun publishPermissionChanged() {
        project.messageBus
            .syncPublisher(PermissionChangedListener.TOPIC)
            .onPermissionChanged(permissionQueue.peek())
    }

    override fun dispose() {
        permissionQueue.clear()
    }
}
