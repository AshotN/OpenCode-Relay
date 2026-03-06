package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.OpenCodeChecker
import com.ashotn.opencode.settings.OpenCodeSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.BorderLayout
import javax.swing.JPanel

class OpenCodeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private var slotDisposable: Disposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")

    init {
        Disposer.register(project, this)
        Disposer.register(this, slotDisposable)
        buildContent()
    }

    fun refresh() {
        // Dispose and recreate the slot so the old InstalledPanel is eagerly cleaned up.
        Disposer.dispose(slotDisposable)
        slotDisposable = Disposer.newDisposable("OpenCodeToolWindowPanel.slot")
        Disposer.register(this, slotDisposable)
        removeAll()
        buildContent()
        revalidate()
        repaint()
    }

    private fun buildContent() {
        val userProvidedPath = OpenCodeSettings.getInstance(project).executablePath
        // findExecutable() performs filesystem I/O (PATH scan, File.isFile, File.canExecute,
        // process spawn for --version). Run it on a pooled thread to avoid blocking the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            val executableInfo = OpenCodeChecker.findExecutable(userProvidedPath.takeIf { it.isNotBlank() })
            ApplicationManager.getApplication().invokeLater {
                val screen = if (executableInfo != null) InstalledPanel(project, slotDisposable, executableInfo) else NotInstalledPanel()
                add(screen, BorderLayout.CENTER)
                revalidate()
                repaint()
            }
        }
    }

    override fun dispose() {}
}
