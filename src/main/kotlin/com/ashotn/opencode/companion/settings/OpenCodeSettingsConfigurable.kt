package com.ashotn.opencode.companion.settings

import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.diff.EditorDiffRenderer
import com.ashotn.opencode.companion.toolwindow.OpenCodeToolWindowPanel
import com.ashotn.opencode.companion.util.BuildUtils
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
                    comment("Some settings are read-only while OpenCode is running. Stop the server to make changes.")
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
            group("Editor") {
                row {
                    checkBox("Show inline diff highlights")
                        .bindSelected(settings::inlineDiffEnabled)
                        .comment(
                            "Renders green/red inline diff highlights in the editor " +
                            "for AI-modified files. Changes take effect immediately."
                        )
                }
            }
            group("Terminal") {
                val terminalSupported = BuildUtils.isEmbeddedTerminalSupported
                row {
                    checkBox("Show inline terminal")
                        .bindSelected(settings::inlineTerminalEnabled)
                        .enabled(terminalSupported)
                        .comment(
                            if (terminalSupported) {
                                "Embeds the OpenCode TUI directly inside the tool window panel when the " +
                                "server is running."
                            } else {
                                "Requires IntelliJ IDEA 2025.3 or later."
                            }
                        )
                }
            }
            group("Diagnostics") {
                row {
                    checkBox("Enable diff trace logging")
                        .bindSelected(settings::diffTraceEnabled)
                        .comment(
                            "Writes a JSONL trace file to the system temp directory " +
                            "(opencode-diff-traces/) for debugging diff pipeline events. " +
                            "Takes effect after restarting the IDE."
                        )
                        .enabled(!running)
                }
                row {
                    checkBox("Include historical diffs in trace")
                        .bindSelected(settings::diffTraceHistoryEnabled)
                        .comment(
                            "Also records events from historical (loaded-on-demand) session diffs " +
                            "in the trace. Only relevant when diff trace logging is enabled. " +
                            "Takes effect after restarting the IDE."
                        )
                        .enabled(!running)
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        EditorDiffRenderer.getInstance(project).onSettingsChanged()
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
