package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.OpenCodeChecker
import com.ashotn.opencode.settings.OpenCodeSettings
import com.intellij.openapi.project.Project
import java.awt.BorderLayout
import javax.swing.JPanel

class OpenCodeToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    init {
        buildContent()
    }

    fun refresh() {
        removeAll()
        buildContent()
        revalidate()
        repaint()
    }

    private fun buildContent() {
        val userProvidedPath = OpenCodeSettings.getInstance(project).executablePath
        val executablePath = OpenCodeChecker.findExecutable(userProvidedPath.takeIf { it.isNotBlank() })
        val screen = if (executablePath != null) InstalledPanel(project) else NotInstalledPanel()
        add(screen, BorderLayout.CENTER)
    }
}
