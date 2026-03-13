package com.ashotn.opencode.companion.terminal

import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.settings.OpenCodeSettings
import com.ashotn.opencode.companion.util.serverUrl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentContainer
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import java.awt.BorderLayout
import java.lang.reflect.Proxy
import javax.swing.JPanel

/**
 * Hosts an embedded classic terminal running `opencode attach <server-url>`.
 *
 * Backed by [LocalTerminalDirectRunner] with a [ShellStartupOptions.shellCommand] override.
 * The terminal is started lazily on the first call to [startIfNeeded] and lives for as long
 * as this panel's parent [Disposable] is alive.
 */
class ClassicTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT when the shell process terminates. */
    private val onTerminated: (() -> Unit)? = null,
    /** Injected process used in tests to verify the kill path without live terminal infrastructure. */
    internal val processOverride: Process? = null,
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

        //For test
        if (processOverride != null) {
            terminalWidget = noOpTerminalWidget()
            return
        }

        try {
            val executablePath = OpenCodePlugin.getInstance(project).openCodeInfo?.path
            if (executablePath.isNullOrBlank()) {
                logger.warn("Skipping classic terminal start because OpenCode executable is unresolved")
                return
            }

            val workingDir = project.basePath ?: System.getProperty("user.home")
            val command = listOf(
                executablePath,
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
        terminalWidget?.component?.requestFocusInWindow()
    }

    override val isStarted: Boolean get() = terminalWidget != null

    override fun stop() = tearDown()

    private fun tearDown() {
        val widget = terminalWidget ?: return
        terminalWidget = null
        remove(widget.component)
        revalidate()
        repaint()
        val process: Process? = processOverride
            ?: widget.ttyConnectorAccessor.ttyConnector
                ?.let { ShellTerminalWidget.getProcessTtyConnector(it)?.process }
        val handle = process?.let { ProcessHandle.of(it.pid()).orElse(null) }
        killProcessTree(handle)
        Disposer.dispose(widget)
    }

    override fun dispose() = tearDown()

    companion object {
        private val logger = Logger.getInstance(ClassicTuiPanel::class.java)

        private fun noOpTerminalWidget(): TerminalWidget =
            Proxy.newProxyInstance(
                TerminalWidget::class.java.classLoader,
                arrayOf(TerminalWidget::class.java, ComponentContainer::class.java, Disposable::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getComponent", "getPreferredFocusableComponent" -> JPanel()
                    "dispose" -> Unit
                    else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
                }
            } as TerminalWidget
    }
}
