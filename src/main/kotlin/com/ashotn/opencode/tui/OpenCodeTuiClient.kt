package com.ashotn.opencode.tui

import com.ashotn.opencode.util.serverUrl
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.net.HttpURLConnection
import java.net.URI

/**
 * Project-level service for sending commands to the running OpenCode TUI via HTTP.
 *
 * This is intentionally separate from [com.ashotn.opencode.diff.OpenCodeDiffService],
 * which owns the SSE connection and diff state. TUI interactions (appending prompt text,
 * switching sessions) are UI/action concerns and belong here.
 *
 * The active server port is supplied by callers via [setPort]; it is updated by
 * [com.ashotn.opencode.OpenCodePlugin] when the server starts and clears when it stops.
 */
@Service(Service.Level.PROJECT)
class OpenCodeTuiClient(private val project: Project) {

    @Volatile private var port: Int = 0

    fun setPort(port: Int) {
        this.port = port
    }

    /**
     * Appends [text] to the TUI's prompt input buffer via POST /tui/append-prompt.
     * Runs the HTTP call on a pooled thread and invokes [onResult] with (success, errorMessage).
     */
    fun appendToTuiPrompt(text: String, onResult: (success: Boolean, error: String?) -> Unit) {
        post("/tui/append-prompt", JsonObject().also { it.addProperty("text", text) }, onResult)
    }

    /**
     * Tells the TUI to navigate to [sessionId] via POST /tui/select-session.
     * Runs the HTTP call on a pooled thread and invokes [onResult] with (success, errorMessage).
     */
    fun selectTuiSession(sessionId: String, onResult: ((success: Boolean, error: String?) -> Unit)? = null) {
        post("/tui/select-session", JsonObject().also { it.addProperty("sessionID", sessionId) }) { success, error ->
            onResult?.invoke(success, error)
        }
    }

    private fun post(path: String, body: JsonObject, onResult: (success: Boolean, error: String?) -> Unit) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, "OpenCode server is not running")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val conn = URI(serverUrl(currentPort, path)).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 3_000
                conn.readTimeout = 5_000
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code in 200..299) onResult(true, null)
                else onResult(false, "Server returned HTTP $code")
            } catch (e: Exception) {
                onResult(false, e.message ?: "Unknown error")
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeTuiClient =
            project.getService(OpenCodeTuiClient::class.java)
    }
}
