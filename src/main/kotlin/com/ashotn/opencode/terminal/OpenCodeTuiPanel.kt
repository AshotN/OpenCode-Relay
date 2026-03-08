package com.ashotn.opencode.terminal

import com.ashotn.opencode.settings.OpenCodeSettings
import com.ashotn.opencode.util.serverUrl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.util.ui.update.UiNotifyConnector
import org.jetbrains.plugins.terminal.LocalBlockTerminalRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Hosts an embedded terminal widget running `opencode attach <server-url>`.
 *
 * The terminal is started lazily on the first call to [startIfNeeded] and lives
 * for as long as this panel's [Disposable] parent is alive.
 *
 * The panel itself is a plain [JPanel] — callers embed it wherever they like in
 * the tool window hierarchy; no Terminal tool-window tab is created.
 *
 * **Sizing:** `startShellTerminalWidget` is called with `deferred = true` so the
 * underlying session only opens after the Swing component has been shown on screen
 * and has a real size (mirrors how the Terminal tool window defers startup via
 * `UiNotifyConnector.doWhenFirstShown`). The attach command is also deferred to
 * the same first-shown callback so it is sent after the shell is fully connected.
 */
class OpenCodeTuiPanel(
    private val project: Project,
    parentDisposable: Disposable,
    /** Invoked on the EDT whenever the running shell terminates unexpectedly. */
    private val onTerminated: (() -> Unit)? = null,
) : JPanel(BorderLayout()), Disposable {

    private var widgetDisposable: Disposable? = null
    private var widget: TerminalWidget? = null

    init {
        Disposer.register(parentDisposable, this)
    }

    /**
     * Starts the embedded terminal (once) and displays it inside this panel.
     * Safe to call multiple times — subsequent calls are no-ops unless the
     * previous shell has already terminated.
     *
     * Must be called on the EDT.
     */
    fun startIfNeeded() {
        if (widget != null) return

        try {
            val runner = LocalBlockTerminalRunner(project)
            val workingDir = project.basePath ?: System.getProperty("user.home")
            val attachCmd = "opencode attach ${serverUrl(OpenCodeSettings.getInstance(project).serverPort)}"
            // opencode handles Ctrl+C internally (calls renderer.destroy(), not process.exit),
            // so there is no signal to intercept. The loop just relaunches attach whenever
            // it exits for any reason. sleep 1 guards against a tight crash-loop if the
            // server is unreachable; it's short enough that a Ctrl+C relaunch feels instant.
            val command = "while true; do $attachCmd; sleep 1; done"

            val options = ShellStartupOptions.Builder()
                .workingDirectory(workingDir)
                .build()

            val disposable = Disposer.newDisposable("OpenCodeTuiPanel.widget")
            Disposer.register(this, disposable)
            widgetDisposable = disposable

            // deferred = true: session starts only after the component is first shown
            // (UiNotifyConnector.doWhenFirstShown internally), so the terminal knows
            // its real pixel size before allocating the PTY columns/rows.
            val newWidget = runner.startShellTerminalWidget(disposable, options, true)
            widget = newWidget

            // When the shell itself exits, tear down and let the owner rebuild.
            newWidget.addTerminationCallback({
                ApplicationManager.getApplication().invokeLater {
                    tearDownWidget()
                    onTerminated?.invoke()
                }
            }, disposable)

            // Embed the terminal Swing component first so it gets a real size.
            add(newWidget.component, BorderLayout.CENTER)
            revalidate()
            repaint()

            // Send the attach command only after the component is shown on screen
            // (and therefore has real dimensions and an open PTY session).
            UiNotifyConnector.doWhenFirstShown(newWidget.component) {
                newWidget.sendCommandToExecute(command)
            }

        } catch (_: NoClassDefFoundError) {
            // Terminal plugin not available – panel stays empty; caller falls back to external.
        } catch (_: Exception) {
            // Any other failure – silently ignore, panel stays empty.
        }
    }

    /** Requests focus on the embedded terminal input. */
    fun focusTerminal() {
        widget?.requestFocus()
    }

    /** Returns true if a live terminal widget is currently running. */
    val isStarted: Boolean get() = widget != null

    /**
     * Stops the running terminal widget and cleans up. Safe to call when
     * already stopped. The next [startIfNeeded] call will create a fresh widget.
     */
    fun stop() = tearDownWidget()

    private fun tearDownWidget() {
        val w = widget ?: return
        widget = null
        widgetDisposable?.let { Disposer.dispose(it) }
        widgetDisposable = null
        remove(w.component)
        revalidate()
        repaint()
    }

    override fun dispose() {
        widget = null
        widgetDisposable = null
    }
}
