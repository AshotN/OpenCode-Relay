package com.ashotn.opencode.permission

import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.PermissionChangedListener
import com.ashotn.opencode.ipc.PermissionReply
import com.ashotn.opencode.util.serverUrl
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue

@Service(Service.Level.PROJECT)
class OpenCodePermissionService(private val project: Project) : Disposable {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 5_000

        fun getInstance(project: Project): OpenCodePermissionService =
            project.getService(OpenCodePermissionService::class.java)
    }

    private val log = logger<OpenCodePermissionService>()
    private val gson = Gson()

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
            val url = URI(serverUrl(currentPort, "/session/${event.sessionId}/permissions/${event.requestId}")).toURL()
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val payload = gson.toJson(mapOf("response" to response.wireValue))
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                if (code in 200..299) {
                    log.info("OpenCodePermissionService: permission reply ${response.wireValue} -> HTTP $code")
                } else {
                    val body = readResponseBody(conn)
                    log.warn("OpenCodePermissionService: permission reply ${response.wireValue} failed -> HTTP $code body=${body.take(300)}")
                }
            } catch (e: Exception) {
                log.warn("OpenCodePermissionService: failed to reply to permission ${event.requestId}", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readResponseBody(conn: HttpURLConnection): String {
        val stream = runCatching { conn.errorStream ?: conn.inputStream }.getOrNull() ?: return ""
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
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
