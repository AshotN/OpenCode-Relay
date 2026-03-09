package com.ashotn.opencode.companion.terminal

import com.ashotn.opencode.companion.settings.OpenCodeSettings
import com.ashotn.opencode.companion.util.BuildUtils
import com.ashotn.opencode.companion.util.serverUrl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Hosts an embedded Reworked Terminal running `opencode attach <server-url>`.
 *
 * Uses the official [TerminalToolWindowTabsManager] API (available since 2025.3)
 * to create a terminal session that is never shown in the Terminal tool window —
 * [shouldAddToToolWindow(false)] keeps it fully detached so it lives only inside
 * this panel. The [TerminalView.component] is embedded directly in the panel's
 * [BorderLayout.CENTER].
 *
 * The terminal is started lazily on the first call to [startIfNeeded] and lives
 * for as long as this panel's parent [Disposable] is alive.
 */
class OpenCodeTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT when the shell process terminates. */
    private val onTerminated: (() -> Unit)? = null,
) : JPanel(BorderLayout()), Disposable {

    private var terminalTab: TerminalToolWindowTab? = null
    private var terminalView: TerminalView? = null

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Creates and embeds the terminal (once). Safe to call multiple times —
     * subsequent calls are no-ops while a session is alive.
     *
     * Must be called on the EDT.
     */
    fun startIfNeeded() {
        if (terminalView != null) return
        if (!BuildUtils.isEmbeddedTerminalSupported) return

        try {
            val workingDir = project.basePath ?: System.getProperty("user.home")
            val attachCmd = "opencode attach ${serverUrl(OpenCodeSettings.getInstance(project).serverPort)}"
            // Loop so the TUI reconnects automatically if opencode attach exits.
            // sleep 1 prevents a tight crash-loop when the server is unreachable.
            val command = "while true; do $attachCmd; sleep 1; done"

            val manager = TerminalToolWindowTabsManager.getInstance(project)

            // shouldAddToToolWindow(false): create the session entirely detached —
            // it never appears as a tab in the Terminal tool window.
            val tab = manager.createTabBuilder()
                .workingDirectory(workingDir)
                .requestFocus(false)
                .shouldAddToToolWindow(false)
                .createTab()

            val view = tab.view
            terminalTab = tab
            terminalView = view

            // Watch sessionState flow: when Terminated the shell has exited.
            view.coroutineScope.launch {
                view.sessionState.collect { state ->
                    if (state is TerminalViewSessionState.Terminated) {
                        ApplicationManager.getApplication().invokeLater {
                            if (terminalView === view) {
                                terminalView = null
                                terminalTab = null
                                remove(view.component)
                                revalidate()
                                repaint()
                                onTerminated?.invoke()
                            }
                        }
                    }
                }
            }

            add(view.component, BorderLayout.CENTER)
            revalidate()
            repaint()

            // Send the attach command once the shell is ready.
            // deferSessionStartUntilUiShown defaults to true in the builder, so
            // the PTY is already sized correctly before the command is sent.
            view.sendText(command + "\n")

        } catch (_: NoClassDefFoundError) {
            // Terminal plugin not available — panel stays empty.
        } catch (_: Exception) {
            // Any other failure — panel stays empty.
        }
    }

    /** True while a terminal session is live. */
    val isStarted: Boolean get() = terminalView != null

    /** Tears down the running session. The next [startIfNeeded] will create a fresh one. */
    fun stop() = tearDown()

    private fun tearDown() {
        val view = terminalView ?: return
        val tab = terminalTab
        terminalView = null
        terminalTab = null
        remove(view.component)
        revalidate()
        repaint()
        // closeTab removes the tab from the manager's tracking list and shuts down the shell.
        if (tab != null) {
            TerminalToolWindowTabsManager.getInstance(project).closeTab(tab)
        } else {
            // Fallback: cancel the scope directly if we somehow lost the tab reference.
            view.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    override fun dispose() {
        tearDown()
    }
}
