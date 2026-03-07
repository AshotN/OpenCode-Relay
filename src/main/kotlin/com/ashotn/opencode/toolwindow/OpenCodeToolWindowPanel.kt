package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.OpenCodeChecker
import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.ServerState
import com.ashotn.opencode.ServerStateListener
import com.ashotn.opencode.diff.DiffHunksChangedListener
import com.ashotn.opencode.diff.OpenCodeDiffService
import com.ashotn.opencode.diff.SessionStateChangedListener
import com.ashotn.opencode.ipc.PermissionChangedListener
import com.ashotn.opencode.permission.OpenCodePermissionService
import com.ashotn.opencode.settings.OpenCodeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import java.awt.CardLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel

class OpenCodeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    companion object {
        private const val CARD_CONTENT = "content"
        private const val CARD_PENDING = "pending"
    }

    private var slotDisposable: Disposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")

    private val outerCardLayout = CardLayout()
    private val outerCardPanel = JPanel(outerCardLayout)
    private val pendingFilesPanel = PendingFilesPanel(project)
    private val syncScheduled = AtomicBoolean(false)
    private val plugin = OpenCodePlugin.getInstance(project)
    private val serverStateListener = ServerStateListener { requestSyncCard() }

    init {
        add(outerCardPanel, BorderLayout.CENTER)
        outerCardPanel.add(JPanel(BorderLayout()), CARD_CONTENT) // placeholder until buildContent runs
        outerCardPanel.add(pendingFilesPanel, CARD_PENDING)

        Disposer.register(project, this)
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

    override fun dispose() {
        plugin.removeListener(serverStateListener)
    }
}
