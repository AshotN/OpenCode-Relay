package com.ashotn.opencode.companion.toolwindow

import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.OpenCodeInfoChangedListener
import com.ashotn.opencode.companion.ServerState
import com.ashotn.opencode.companion.ServerStateListener
import com.ashotn.opencode.companion.core.DiffHunksChangedListener
import com.ashotn.opencode.companion.core.OpenCodeCoreService
import com.ashotn.opencode.companion.core.session.SessionStateChangedListener
import com.ashotn.opencode.companion.ipc.PermissionChangedListener
import com.ashotn.opencode.companion.permission.OpenCodePermissionService
import com.ashotn.opencode.companion.settings.OpenCodeSettings
import com.ashotn.opencode.companion.settings.OpenCodeSettings.TerminalEngine
import com.ashotn.opencode.companion.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.companion.terminal.ClassicTuiPanel
import com.ashotn.opencode.companion.terminal.ReworkedTuiPanel
import com.ashotn.opencode.companion.terminal.TuiPanel
import com.ashotn.opencode.companion.util.BuildUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Graphics
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

class OpenCodeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private const val CARD_CONTENT = "content"
        private const val CARD_PENDING = "pending"

        /** Installs a theme-adaptive, 1px divider on [pane] so it matches the IDE border color. */
        fun applyThemedDivider(pane: JSplitPane) {
            pane.setUI(object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider =
                    object : BasicSplitPaneDivider(this) {
                        override fun paint(g: Graphics) {
                            g.color = JBColor.border()
                            g.fillRect(0, 0, width, height)
                        }
                    }
            })
            pane.border = null
        }
    }

    private var slotDisposable: Disposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")

    private val outerCardLayout = CardLayout()
    private val outerCardPanel = JPanel(outerCardLayout)
    private val pendingFilesPanel = PendingFilesPanel(project, this)
    private var tuiPanel: TuiPanel = createTuiPanel()
    private var activeTuiEngine: TerminalEngine = effectiveEngine(OpenCodeSettings.getInstance(project).terminalEngine)
    private val syncScheduled = AtomicBoolean(false)
    private val plugin = OpenCodePlugin.getInstance(project)
    private val serverStateListener = ServerStateListener { requestSyncCard() }

    // Split pane that stacks content (top) and TUI (bottom).
    // The TUI half is hidden until the server is READY.
    private val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, outerCardPanel, tuiPanel.component.apply {
        minimumSize = JBUI.size(0, 120)
    }).apply {
        // resizeWeight = 0.0: all resize delta goes to the bottom (terminal) panel.
        // This means when the tool window grows, the terminal expands rather than
        // the session/file section, making the terminal the priority for new space.
        resizeWeight = 0.0
        isContinuousLayout = true
        border = null
        // Hide the divider bar until the server is READY.
        dividerSize = 0
        // Start with the divider all the way down so TUI is not visible yet.
        dividerLocation = Int.MAX_VALUE
        applyThemedDivider(this)
    }

    init {
        add(splitPane, BorderLayout.CENTER)
        outerCardPanel.add(JPanel(BorderLayout()), CARD_CONTENT) // placeholder until buildContent runs
        outerCardPanel.add(pendingFilesPanel, CARD_PENDING)

        Disposer.register(plugin, this)
        Disposer.register(this, slotDisposable)

        project.messageBus.connect(this).subscribe(
            DiffHunksChangedListener.TOPIC,
            DiffHunksChangedListener { _ -> requestSyncCard() }
        )

        project.messageBus.connect(this).subscribe(
            SessionStateChangedListener.TOPIC,
            SessionStateChangedListener { requestSyncCard() }
        )

        project.messageBus.connect(this).subscribe(
            PermissionChangedListener.TOPIC,
            PermissionChangedListener { requestSyncCard() }
        )

        plugin.addListener(serverStateListener)

        project.messageBus.connect(this).subscribe(
            OpenCodeSettingsChangedListener.TOPIC,
            OpenCodeSettingsChangedListener { _, _ ->
                swapTuiPanelIfEngineChanged()
                requestSyncCard()
            }
        )

        project.messageBus.connect(this).subscribe(
            OpenCodeInfoChangedListener.TOPIC,
            OpenCodeInfoChangedListener { _ ->
                // Dispose and recreate the slot so the old InstalledPanel is eagerly cleaned up.
                Disposer.dispose(slotDisposable)
                slotDisposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")
                Disposer.register(this, slotDisposable)
                buildContent()
            }
        )

        buildContent()
    }

    private fun requestSyncCard() {
        if (!syncScheduled.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater {
            syncScheduled.set(false)
            syncCard()
        }
    }

    private fun syncCard() {
        val serverReady = plugin.serverState == ServerState.READY

        // Show / hide TUI panel based on server state and inline terminal setting.
        val inlineTerminal = serverReady && OpenCodeSettings.getInstance(project).inlineTerminalEnabled
        if (inlineTerminal) {
            tuiPanel.startIfNeeded()
            if (tuiPanel.isStarted) {
                // Restore the split whenever the divider is hidden (e.g. after a reset).
                // Run after layout so the panel has a real height.
                if (splitPane.dividerSize == 0) {
                    ApplicationManager.getApplication().invokeLater {
                        val total = splitPane.height
                        if (total > 0) {
                            splitPane.dividerSize = JBUI.scale(2)
                            splitPane.dividerLocation = (total * 0.25).toInt()
                        }
                    }
                }
            } else {
                splitPane.dividerSize = 0
                splitPane.dividerLocation = Int.MAX_VALUE
            }
        } else {
            // Inline terminal disabled or server not running – stop any running widget
            // so it doesn't linger in the background, then collapse the divider.
            if (tuiPanel.isStarted) tuiPanel.stop()
            splitPane.dividerSize = 0
            splitPane.dividerLocation = Int.MAX_VALUE
        }

        val showPending = when (plugin.serverState) {
            ServerState.READY -> {
                val diffService = OpenCodeCoreService.getInstance(project)
                val permissionService = OpenCodePermissionService.getInstance(project)
                val hasPending = diffService.allTrackedFiles().isNotEmpty()
                val hasSessions = diffService.listSessions().isNotEmpty()
                val hasPermission = permissionService.currentPermission() != null
                hasPending || hasSessions || hasPermission
            }

            else -> false
        }

        outerCardLayout.show(outerCardPanel, if (showPending) CARD_PENDING else CARD_CONTENT)
    }

    fun focusTerminal() {
        tuiPanel.focusTerminal()
    }

    /**
     * If the user changed the terminal engine in settings, tears down the current
     * [tuiPanel], swaps in a fresh instance backed by the new engine, and re-wires
     * it into the split pane — all without requiring an IDE restart.
     */
    private fun swapTuiPanelIfEngineChanged() {
        val effectiveEngine = effectiveEngine(OpenCodeSettings.getInstance(project).terminalEngine)
        if (effectiveEngine == activeTuiEngine) return

        // Stop and dispose the old panel.
        if (tuiPanel.isStarted) tuiPanel.stop()
        Disposer.dispose(tuiPanel)
        splitPane.bottomComponent = null

        // Create and wire up the new panel.
        val newPanel = createTuiPanel()
        newPanel.component.minimumSize = JBUI.size(0, 120)
        splitPane.bottomComponent = newPanel.component
        tuiPanel = newPanel
        activeTuiEngine = effectiveEngine
    }

    private fun buildContent() {
        val executableInfo = plugin.openCodeInfo
        val screen = when {
            executableInfo != null -> InstalledPanel(project, slotDisposable, executableInfo)
            plugin.isExecutableResolutionCompleted -> NotInstalledPanel()
            else -> ResolvingExecutablePanel()
        }

        // Replace the content card with the new screen
        val existingContent = outerCardPanel.components.firstOrNull { it != pendingFilesPanel }
        if (existingContent != null) outerCardPanel.remove(existingContent)
        outerCardPanel.add(screen, CARD_CONTENT)
        syncCard()
        revalidate()
        repaint()
    }

    /**
     * Creates the appropriate [TuiPanel] implementation for the currently configured
     * [OpenCodeSettings.terminalEngine].  Falls back to [ClassicTuiPanel] when the
     * reworked engine is requested but the IDE version does not support it.
     */
    private fun createTuiPanel(): TuiPanel {
        val settings = OpenCodeSettings.getInstance(project)
        return if (effectiveEngine(settings.terminalEngine) == TerminalEngine.REWORKED) {
            ReworkedTuiPanel(project, this, onTerminated = { requestSyncCard() })
        } else {
            ClassicTuiPanel(project, this, onTerminated = { requestSyncCard() })
        }
    }

    /**
     * Resolves the effective [TerminalEngine] to use, falling back to [TerminalEngine.CLASSIC]
     * when [TerminalEngine.REWORKED] is requested but the IDE version does not support it.
     */
    private fun effectiveEngine(requested: TerminalEngine): TerminalEngine =
        if (requested == TerminalEngine.REWORKED && !BuildUtils.isEmbeddedTerminalSupported) {
            TerminalEngine.CLASSIC
        } else {
            requested
        }

    private var disposed = false

    override fun dispose() {
        if (disposed) return
        disposed = true
        plugin.removeListener(serverStateListener)
    }
}
