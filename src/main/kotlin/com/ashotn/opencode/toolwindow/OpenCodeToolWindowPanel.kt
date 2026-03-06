package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.OpenCodeChecker
import com.ashotn.opencode.settings.OpenCodeSettings
import com.intellij.openapi.Disposable
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
        val executableInfo = OpenCodeChecker.findExecutable(userProvidedPath.takeIf { it.isNotBlank() })
        val screen = if (executableInfo != null) InstalledPanel(project, slotDisposable) else NotInstalledPanel()
        add(screen, BorderLayout.CENTER)
    }

    override fun dispose() {
        // slotDisposable (and its children) will be disposed transitively by Disposer.
    }
}
