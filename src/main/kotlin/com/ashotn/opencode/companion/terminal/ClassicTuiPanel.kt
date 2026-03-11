package com.ashotn.opencode.companion.terminal

import com.ashotn.opencode.companion.settings.OpenCodeSettings
import com.ashotn.opencode.companion.util.serverUrl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Hosts an embedded **classic** terminal (backed by [LocalTerminalDirectRunner] /
 * [org.jetbrains.plugins.terminal.ShellTerminalWidget]) running
 * `opencode attach <server-url>`.
 *
 * This engine works on all IntelliJ versions supported by the plugin (since 233)
 * without any version-gated API.  It is wired up via [LocalTerminalDirectRunner]
 * with an explicit [ShellStartupOptions.shellCommand] override so it runs our
 * specific command instead of the user's default shell.
 *
 * The terminal is started lazily on the first call to [startIfNeeded] and lives
 * for as long as this panel's parent [Disposable] is alive.
 */
class ClassicTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT when the shell process terminates. */
    private val onTerminated: (() -> Unit)? = null,
) : JPanel(BorderLayout()), TuiPanel, Disposable {

    private var terminalWidget: TerminalWidget? = null

    init {
        Disposer.register(parentDisposable, this)
    }

    override val component: JPanel get() = this

    /**
     * Creates and embeds the terminal (once). Safe to call multiple times —
     * subsequent calls are no-ops while a session is alive.
     *
     * Must be called on the EDT.
     */
    override fun startIfNeeded() {
        if (terminalWidget != null) return

        try {
            val workingDir = project.basePath ?: System.getProperty("user.home")
            val command = listOf(
                "opencode",
                "attach",
                serverUrl(OpenCodeSettings.getInstance(project).serverPort),
            )

            val runner = LocalTerminalDirectRunner.createTerminalRunner(project)
            val startupOptions = ShellStartupOptions.Builder()
                .workingDirectory(workingDir)
                .shellCommand(command)
                .build()

            val widget = runner.startShellTerminalWidget(this, startupOptions, true)
            terminalWidget = widget
            Disposer.register(this, widget)

            // When the shell process exits, clean up and notify the owner.
            widget.addTerminationCallback({
                ApplicationManager.getApplication().invokeLater {
                    logger.debug("Classic terminal process terminated")
                    if (terminalWidget === widget) {
                        terminalWidget = null
                        remove(widget.component)
                        revalidate()
                        repaint()
                        onTerminated?.invoke()
                    }
                }
            }, this)

            add(widget.component, BorderLayout.CENTER)
            revalidate()
            repaint()

        } catch (e: NoClassDefFoundError) {
            logger.warn("Classic terminal classes unavailable", e)
            // Panel stays empty.
        } catch (e: Exception) {
            logger.warn("Failed to start classic terminal", e)
            // Panel stays empty.
        }
    }

    override fun focusTerminal() {
        terminalWidget?.requestFocus()
    }

    override val isStarted: Boolean get() = terminalWidget != null

    override fun stop() = tearDown()

    private fun tearDown() {
        val widget = terminalWidget ?: return
        terminalWidget = null
        remove(widget.component)
        revalidate()
        repaint()
        // Disposing the widget will trigger the termination callback if the process
        // is still alive, but since we've already cleared terminalWidget the guard
        // (terminalWidget === widget) inside the callback will short-circuit it.
        Disposer.dispose(widget)
    }

    override fun dispose() {
        val widget = terminalWidget ?: return
        terminalWidget = null
        remove(widget.component)
        revalidate()
        repaint()
        Disposer.dispose(widget)
    }

    companion object {
        private val logger = Logger.getInstance(ClassicTuiPanel::class.java)
    }
}
