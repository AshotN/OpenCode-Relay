package com.ashotn.opencode.companion.settings

import com.ashotn.opencode.companion.OpenCodeChecker
import com.ashotn.opencode.companion.OpenCodeInfo
import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.ResolvedInfoChangedListener
import com.ashotn.opencode.companion.core.EditorDiffRenderer
import com.ashotn.opencode.companion.settings.OpenCodeSettings.TerminalEngine
import com.ashotn.opencode.companion.util.BuildUtils
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.*
import java.util.concurrent.atomic.AtomicReference

class OpenCodeSettingsConfigurable(private val project: Project) :
    BoundConfigurable("OpenCode Companion") {

    override fun createPanel(): DialogPanel {
        val settings = OpenCodeSettings.getInstance(project)
        return panel {
            group("Executable") {
                row("OpenCode Path:") {
                    textField()
                        .bindText(settings::executablePath)
                        .comment("Path to the opencode executable. Leave blank to auto-detect.")
                        .align(AlignX.FILL)
                }
            }
            group("Server") {
                row("Server Port:") {
                    intTextField(1024..65535)
                        .bindIntText(settings::serverPort)
                        .comment("Port the OpenCode server listens on (default: 4096)")
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
                val reworkedSupported = BuildUtils.isEmbeddedTerminalSupported
                row {
                    checkBox("Show inline terminal")
                        .bindSelected(settings::inlineTerminalEnabled)
                        .comment("Embeds the OpenCode TUI directly inside the tool window panel when the server is running.")
                }
                buttonsGroup("Terminal engine:") {
                    row {
                        radioButton("Classic (Recommended)", TerminalEngine.CLASSIC)
                            .comment("Legacy JediTerm widget. Works on all supported IDE versions.")
                    }
                    row {
                        radioButton("Reworked", TerminalEngine.REWORKED)
                            .enabled(reworkedSupported)
                            .comment(
                                if (reworkedSupported) "New terminal engine (IntelliJ 2025.3+)."
                                else "Requires IntelliJ 2025.3 or later."
                            )
                    }
                }.bind(settings::terminalEngine)
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
                }
                row {
                    checkBox("Include historical diffs in trace")
                        .bindSelected(settings::diffTraceHistoryEnabled)
                        .comment(
                            "Also records events from historical (loaded-on-demand) session diffs " +
                                    "in the trace. Only relevant when diff trace logging is enabled. " +
                                    "Takes effect after restarting the IDE."
                        )
                }
            }
        }
    }

    override fun apply() {
        val settings = OpenCodeSettings.getInstance(project)
        val plugin = OpenCodePlugin.getInstance(project)

        // 1. Snapshot old values before the UI writes them.
        val oldPort = settings.serverPort
        val oldPath = settings.executablePath

        // Write the UI values to settings so we can read the new values.
        super.apply()

        val newPort = settings.serverPort
        val newPath = settings.executablePath

        // 2. Re-resolve the executable with a modal spinner (~3 s max).
        val resolvedRef = AtomicReference<OpenCodeInfo?>()
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                resolvedRef.set(
                    OpenCodeChecker.findExecutable(newPath.takeIf { it.isNotBlank() })
                )
            },
            "Resolving OpenCode\u2026",
            false,
            project
        )

        val info = resolvedRef.get()

        // 3. If resolution failed, show error. Settings are already written but
        //    the UI will show NotInstalledPanel via the topic.
        if (info == null) {
            Messages.showErrorDialog(
                project,
                "Could not find a valid OpenCode executable. Check the path and try again.",
                "OpenCode Resolution Failed"
            )
            // Publish null so the tool window transitions to NotInstalledPanel.
            plugin.resolvedInfo = null
            project.messageBus.syncPublisher(ResolvedInfoChangedListener.TOPIC)
                .onResolvedInfoChanged(null)
            EditorDiffRenderer.getInstance(project).onSettingsChanged()
            return
        }

        // 4. If we own the process and port/path changed, warn the user.
        val portOrPathChanged = newPort != oldPort || newPath != oldPath
        val serverRunning = plugin.isRunning
        val owned = plugin.ownsProcess

        if (serverRunning && owned && portOrPathChanged) {
            val confirmed = Messages.showYesNoDialog(
                project,
                "The OpenCode server is currently running. Applying these changes will stop it. " +
                        "You will need to start it again manually.",
                "Stop OpenCode Server?",
                "Stop Server",
                "Cancel",
                Messages.getWarningIcon()
            ) == Messages.YES

            if (!confirmed) {
                // Revert settings to old values and re-resolve.
                settings.serverPort = oldPort
                settings.executablePath = oldPath
                reset()
                return
            }
        }

        // 6. Publish the freshly resolved info to the topic.
        plugin.resolvedInfo = info
        project.messageBus.syncPublisher(ResolvedInfoChangedListener.TOPIC)
            .onResolvedInfoChanged(info)

        // 7. If we owned the process and user confirmed stop → stop the server.
        if (serverRunning && owned && portOrPathChanged) {
            plugin.stopServer()
        }

        // 8. If we don't own the process → re-attach to the new port.
        if (serverRunning && !owned && newPort != oldPort) {
            plugin.reattach(newPort)
        }

        EditorDiffRenderer.getInstance(project).onSettingsChanged()
    }
}
