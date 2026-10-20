package com.ashotn.opencode.relay.tui

import com.ashotn.opencode.relay.api.session.SessionApiClient
import com.ashotn.opencode.relay.api.transport.ApiError
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.api.transport.OpenCodeHttpTransport
import com.ashotn.opencode.relay.api.transport.isAuthenticationFailure
import com.ashotn.opencode.relay.core.OpenCodeCoreService
import com.ashotn.opencode.relay.settings.OpenCodeServerAuth
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.terminal.TuiPanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Project-level service for driving the running OpenCode TUI.
 *
 * OpenCode 2.x removed the v1 `/tui/` control routes, so the TUI is steered through its own
 * terminal instead: references are pasted into the prompt input ([appendToTuiPrompt]) and a
 * session is switched by relaunching the TUI against `--session=<id>` ([selectTuiSession]).
 *
 * The [TuiPanel] that hosts the terminal is registered by
 * [com.ashotn.opencode.relay.toolwindow.OpenCodeToolWindowPanel] via [setTuiPanel]. The active
 * server port is supplied via [setPort] by [com.ashotn.opencode.relay.OpenCodePlugin].
 */
@Service(Service.Level.PROJECT)
class OpenCodeTuiClient(private val project: Project) {

    private val serverAuth = OpenCodeServerAuth.getInstance(project)
    private val transport = OpenCodeHttpTransport(authorizationHeaderProvider = serverAuth::connectionAuthorizationHeader)
    private val sessionApiClient = SessionApiClient(transport)

    @Volatile
    private var port: Int = 0

    @Volatile
    private var tuiPanel: TuiPanel? = null

    fun setPort(port: Int) {
        this.port = port
    }

    /** Registers the embedded terminal panel. Must be called on the EDT. */
    fun setTuiPanel(panel: TuiPanel?) {
        this.tuiPanel = panel
    }

    /**
     * Appends [text] to the TUI's prompt input by pasting it into the embedded terminal
     * (bracketed paste), without submitting it. Runs on the EDT.
     */
    fun appendToTuiPrompt(text: String, onResult: (success: Boolean, error: String?) -> Unit) {
        val panel = tuiPanel
        if (panel == null) {
            onResult(false, "OpenCode TUI is not available")
            return
        }
        tuiUnavailableReason()?.let {
            onResult(false, it)
            return
        }
        if (!panel.isStarted) {
            onResult(false, "The embedded OpenCode TUI isn't running — open the OpenCode Relay tool window.")
            return
        }
        if (panel.appendToPrompt(text)) {
            onResult(true, null)
        } else {
            onResult(false, "Could not send the reference to the OpenCode TUI.")
        }
    }

    /**
     * Switches the TUI to [sessionId] by relaunching it against `--session=<id>`. Runs on the
     * EDT. The caller is responsible for updating the plugin's local session selection.
     */
    fun selectTuiSession(sessionId: String, onResult: ((success: Boolean, error: String?) -> Unit)? = null) {
        val panel = tuiPanel
        if (panel == null) {
            onResult?.invoke(false, "OpenCode TUI is not available")
            return
        }
        tuiUnavailableReason()?.let {
            onResult?.invoke(false, it)
            return
        }
        panel.relaunchForSession(sessionId)
        onResult?.invoke(true, null)
    }

    /**
     * Creates a new root session via POST /api/session and switches the TUI to it.
     *
     * If the newest known session has no messages, no tracked files, and is not busy, we reuse
     * it instead of creating a new one — there is no point in accumulating blank sessions. The
     * [onResult] callback receives reused=true in that case.
     *
     * The session is created on a pooled thread; the TUI relaunch, the local selection, and
     * [onResult] all run on the EDT.
     */
    fun createSessionAndSelectInTui(
        onResult: (success: Boolean, sessionId: String?, reused: Boolean, error: String?) -> Unit,
    ) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, null, false, "OpenCode server is not running")
            return
        }
        val panel = tuiPanel
        if (panel == null) {
            onResult(false, null, false, "OpenCode TUI is not available")
            return
        }
        tuiUnavailableReason()?.let {
            onResult(false, null, false, it)
            return
        }

        val diffService = OpenCodeCoreService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val existingEmpty = diffService.listSessions()
                .maxByOrNull { it.updatedAtMillis }
                ?.takeIf { it.parentSessionId == null && !it.hasMessages && it.trackedFileCount == 0 && !it.isBusy }

            val targetSessionId: String
            val reused: Boolean
            if (existingEmpty != null) {
                targetSessionId = existingEmpty.sessionId
                reused = true
            } else {
                targetSessionId = when (val created = sessionApiClient.createSession(currentPort)) {
                    is ApiResult.Success -> created.value.sessionId
                    is ApiResult.Failure -> {
                        ApplicationManager.getApplication().invokeLater {
                            if (!project.isDisposed) onResult(false, null, false, apiErrorMessage(created.error))
                        }
                        return@executeOnPooledThread
                    }
                }
                reused = false
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                panel.relaunchForSession(targetSessionId)
                diffService.selectSession(targetSessionId)
                onResult(true, targetSessionId, reused, null)
            }
        }
    }

    /**
     * Deletes a session via DELETE /api/session/{sessionId}.
     * On success, removes the session from local state so the UI updates immediately.
     */
    fun deleteSession(sessionId: String, onResult: (success: Boolean, error: String?) -> Unit) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, "OpenCode server is not running")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val coreService = OpenCodeCoreService.getInstance(project)
            when (val result = sessionApiClient.deleteSession(currentPort, sessionId)) {
                is ApiResult.Success -> {
                    coreService.removeSession(sessionId)
                    onResult(true, null)
                }

                is ApiResult.Failure -> onResult(false, apiErrorMessage(result.error))
            }
            coreService.refreshSessionHierarchy()
        }
    }

    /**
     * Renames a session via PATCH /api/session/{sessionId}.
     *
     * v2 returns 204 without a body, so the updated title is picked up by the
     * hierarchy refresh that follows.
     */
    fun renameSession(sessionId: String, newTitle: String, onResult: (success: Boolean, error: String?) -> Unit) {
        val currentPort = port
        if (currentPort <= 0) {
            onResult(false, "OpenCode server is not running")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val coreService = OpenCodeCoreService.getInstance(project)
            when (val result = sessionApiClient.updateSession(currentPort, sessionId, newTitle)) {
                is ApiResult.Success -> onResult(true, null)
                is ApiResult.Failure -> onResult(false, apiErrorMessage(result.error))
            }
            coreService.refreshSessionHierarchy()
        }
    }

    private fun apiErrorMessage(error: ApiError): String = when (error) {
        is ApiError.HttpError -> if (error.isAuthenticationFailure()) {
            "OpenCode authentication failed. Update Server Authentication settings."
        } else {
            "Server returned HTTP ${error.statusCode}"
        }
        is ApiError.NetworkError -> error.message
        is ApiError.ParseError -> error.message
    }

    /** A reason the TUI can't be driven, or null when it is. */
    private fun tuiUnavailableReason(): String? =
        if (OpenCodeSettings.getInstance(project).inlineTerminalEnabled) {
            null
        } else {
            "Enable the embedded OpenCode terminal in Settings to use this feature."
        }

    companion object {
        fun getInstance(project: Project): OpenCodeTuiClient =
            project.getService(OpenCodeTuiClient::class.java)
    }
}
