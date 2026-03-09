package com.ashotn.opencode.tui

import com.ashotn.opencode.diff.OpenCodeDiffService
import com.ashotn.opencode.util.serverUrl
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    /**
     * Creates a new root session via POST /session and switches the TUI to it via
     * POST /tui/select-session.
     */
    fun createSessionAndSelectInTui(
        onResult: (success: Boolean, sessionId: String?, error: String?) -> Unit,
    ) {
        createSession { success, sessionId, error ->
            if (!success || sessionId.isNullOrBlank()) {
                onResult(false, sessionId, error)
                return@createSession
            }

            selectTuiSession(sessionId) { selectSuccess, selectError ->
                if (!selectSuccess) {
                    onResult(false, sessionId, selectError)
                    return@selectTuiSession
                }

                val diffService = OpenCodeDiffService.getInstance(project)
                diffService.selectSession(sessionId)
                onResult(true, sessionId, null)
            }
        }
    }

    /**
     * Creates a new session via POST /session and returns the created session ID.
     */
    private fun createSession(onResult: (success: Boolean, sessionId: String?, error: String?) -> Unit) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, null, "OpenCode server is not running")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val createResult = postForJson(currentPort, "/session", JsonObject())
            if (!createResult.success) {
                onResult(false, null, createResult.error)
                return@executeOnPooledThread
            }

            val sessionId = parseSessionIdFromCreateResponse(createResult.body)
            if (sessionId.isNullOrBlank()) {
                onResult(false, null, "Server returned an invalid session response")
                return@executeOnPooledThread
            }

            onResult(true, sessionId, null)
        }
    }

    private fun post(path: String, body: JsonObject, onResult: (success: Boolean, error: String?) -> Unit) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, "OpenCode server is not running")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = postForJson(currentPort, path, body)
            onResult(result.success, result.error)
        }
    }

    private fun parseSessionIdFromCreateResponse(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            JsonParser.parseString(body).asJsonObject.get("id")?.asString
        } catch (_: Exception) {
            null
        }
    }

    private data class PostResult(
        val success: Boolean,
        val body: String?,
        val error: String?,
    )

    private fun postForJson(currentPort: Int, path: String, body: JsonObject): PostResult {
        val conn = URI(serverUrl(currentPort, path)).toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 3_000
            conn.readTimeout = 5_000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }
            if (code in 200..299) PostResult(success = true, body = responseBody, error = null)
            else PostResult(success = false, body = null, error = "Server returned HTTP $code")
        } catch (e: Exception) {
            PostResult(success = false, body = null, error = e.message ?: "Unknown error")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun getInstance(project: Project): OpenCodeTuiClient =
            project.getService(OpenCodeTuiClient::class.java)
    }
}
