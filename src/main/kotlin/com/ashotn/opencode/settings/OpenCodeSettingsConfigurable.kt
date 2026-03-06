package com.ashotn.opencode.settings

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.toolwindow.OpenCodeToolWindowPanel
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.dsl.builder.*
import javax.swing.SwingUtilities

class OpenCodeSettingsConfigurable(private val project: Project) :
    BoundConfigurable("OpenCode") {

    override fun createPanel(): DialogPanel {
        val settings = OpenCodeSettings.getInstance(project)
        val running = OpenCodePlugin.getInstance(project).isRunning
        return panel {
            if (running) {
                row {
                    comment("Settings are read-only while OpenCode is running. Stop the server to make changes.")
                }
            }
            group("Executable") {
                row("OpenCode Path:") {
                    textField()
                        .bindText(settings::executablePath)
                        .comment("Path to the opencode executable. Leave blank to auto-detect.")
                        .align(AlignX.FILL)
                        .enabled(!running)
                }
            }
            group("Server") {
                row("Server Port:") {
                    intTextField(1024..65535)
                        .bindIntText(settings::serverPort)
                        .comment("Port the OpenCode server listens on (default: 4096)")
                        .enabled(!running)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        refreshToolWindowPanel()
    }

    private fun refreshToolWindowPanel() {
        SwingUtilities.invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("OpenCode") ?: return@invokeLater
            val content = toolWindow.contentManager.getContent(0) ?: return@invokeLater
            (content.component as? OpenCodeToolWindowPanel)?.refresh()
        }
    }
}
