package com.ashotn.opencode.tui

import com.ashotn.opencode.api.session.SessionApiClient
import com.ashotn.opencode.api.transport.ApiError
import com.ashotn.opencode.api.transport.ApiResult
import com.ashotn.opencode.api.tui.TuiApiClient
import com.ashotn.opencode.diff.OpenCodeDiffService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

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

    private val sessionApiClient = SessionApiClient()
    private val tuiApiClient = TuiApiClient()

    @Volatile private var port: Int = 0

    fun setPort(port: Int) {
        this.port = port
    }

    /**
     * Appends [text] to the TUI's prompt input buffer via POST /tui/append-prompt.
     * Runs the HTTP call on a pooled thread and invokes [onResult] with (success, errorMessage).
     */
    fun appendToTuiPrompt(text: String, onResult: (success: Boolean, error: String?) -> Unit) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, "OpenCode server is not running")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            when (val result = tuiApiClient.appendPrompt(currentPort, text)) {
                is ApiResult.Success -> {
                    if (result.value) onResult(true, null)
                    else onResult(false, "Request was rejected by server")
                }

                is ApiResult.Failure -> onResult(false, apiErrorMessage(result.error))
            }
        }
    }

    /**
     * Tells the TUI to navigate to [sessionId] via POST /tui/select-session.
     * Runs the HTTP call on a pooled thread and invokes [onResult] with (success, errorMessage).
     */
    fun selectTuiSession(sessionId: String, onResult: ((success: Boolean, error: String?) -> Unit)? = null) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult?.invoke(false, "OpenCode server is not running")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            when (val result = tuiApiClient.selectSession(currentPort, sessionId)) {
                is ApiResult.Success -> {
                    if (result.value) onResult?.invoke(true, null)
                    else onResult?.invoke(false, "Request was rejected by server")
                }

                is ApiResult.Failure -> onResult?.invoke(false, apiErrorMessage(result.error))
            }
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
            when (val createResult = sessionApiClient.createSession(currentPort)) {
                is ApiResult.Success -> onResult(true, createResult.value.sessionId, null)
                is ApiResult.Failure -> onResult(false, null, apiErrorMessage(createResult.error))
            }
        }
    }

    private fun apiErrorMessage(error: ApiError): String = when (error) {
        is ApiError.HttpError -> "Server returned HTTP ${error.statusCode}"
        is ApiError.NetworkError -> error.message
        is ApiError.ParseError -> error.message
    }

    companion object {
        fun getInstance(project: Project): OpenCodeTuiClient =
            project.getService(OpenCodeTuiClient::class.java)
    }
}
