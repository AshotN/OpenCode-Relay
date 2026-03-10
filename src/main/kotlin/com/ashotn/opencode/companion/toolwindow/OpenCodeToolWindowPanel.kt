package com.ashotn.opencode.companion.toolwindow

import com.ashotn.opencode.companion.OpenCodeChecker
import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.ServerState
import com.ashotn.opencode.companion.ServerStateListener
import com.ashotn.opencode.companion.diff.DiffHunksChangedListener
import com.ashotn.opencode.companion.diff.OpenCodeDiffService
import com.ashotn.opencode.companion.diff.SessionStateChangedListener
import com.ashotn.opencode.companion.ipc.PermissionChangedListener
import com.ashotn.opencode.companion.permission.OpenCodePermissionService
import com.ashotn.opencode.companion.settings.OpenCodeSettings
import com.ashotn.opencode.companion.terminal.OpenCodeTuiPanel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.awt.CardLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JSplitPane

class OpenCodeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private const val CARD_CONTENT = "content"
        private const val CARD_PENDING = "pending"
    }

    private var slotDisposable: Disposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")

    private val outerCardLayout = CardLayout()
    private val outerCardPanel = JPanel(outerCardLayout)
    private val pendingFilesPanel = PendingFilesPanel(project, this)
    private val tuiPanel = OpenCodeTuiPanel(project, this, onTerminated = { requestSyncCard() })
    private val syncScheduled = AtomicBoolean(false)
    private val plugin = OpenCodePlugin.getInstance(project)
    private val serverStateListener = ServerStateListener { requestSyncCard() }

    // Split pane that stacks content (top) and TUI (bottom).
    // The TUI half is hidden until the server is READY.
    private val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, outerCardPanel, tuiPanel).apply {
        // resizeWeight = 1.0: all resize delta is absorbed by the top panel.
        // This keeps the terminal (bottom) pane at a stable size when the tool window
        // is resized, preventing it from shifting up and becoming hidden (X11/XToolkit).
        resizeWeight = 1.0
        isContinuousLayout = true
        border = null
        // Hide the divider bar until the server is READY.
        dividerSize = 0
        // Start with the divider all the way down so TUI is not visible yet.
        dividerLocation = Int.MAX_VALUE
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
            // Restore the split whenever the divider is hidden (e.g. after a reset).
            // Run after layout so the panel has a real height.
            if (splitPane.dividerSize == 0) {
                ApplicationManager.getApplication().invokeLater {
                    val total = splitPane.height
                    if (total > 0) {
                        splitPane.dividerSize = 4
                        splitPane.dividerLocation = (total * 0.45).toInt()
                    }
                }
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
                val diffService = OpenCodeDiffService.getInstance(project)
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

    fun refresh() {
        // Dispose and recreate the slot so the old InstalledPanel is eagerly cleaned up.
        Disposer.dispose(slotDisposable)
        slotDisposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")
        Disposer.register(this, slotDisposable)
        buildContent()
    }

    private fun buildContent() {
        val userProvidedPath = OpenCodeSettings.getInstance(project).executablePath
        // findExecutable() performs filesystem I/O (PATH scan, File.isFile, File.canExecute,
        // process spawn for --version). Run it on a pooled thread to avoid blocking the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val executableInfo = OpenCodeChecker.findExecutable(userProvidedPath.takeIf { it.isNotBlank() })
            ApplicationManager.getApplication().invokeLater {
                val screen = if (executableInfo != null) InstalledPanel(project, slotDisposable, executableInfo) else NotInstalledPanel()
                // Replace the content card with the new screen
                val existingContent = outerCardPanel.components.firstOrNull { it != pendingFilesPanel }
                if (existingContent != null) outerCardPanel.remove(existingContent)
                outerCardPanel.add(screen, CARD_CONTENT)
                syncCard()
                revalidate()
                repaint()
            }
        }
    }

    private var disposed = false

    override fun dispose() {
        if (disposed) return
        disposed = true
        plugin.removeListener(serverStateListener)
    }
}
