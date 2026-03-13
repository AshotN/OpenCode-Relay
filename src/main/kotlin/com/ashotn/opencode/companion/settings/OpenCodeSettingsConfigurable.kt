package com.ashotn.opencode.companion.settings

import com.ashotn.opencode.companion.OpenCodeChecker
import com.ashotn.opencode.companion.OpenCodeInfo
import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.OpenCodeInfoChangedListener
import com.ashotn.opencode.companion.core.EditorDiffRenderer
import com.ashotn.opencode.companion.settings.OpenCodeSettings.TerminalEngine
import com.ashotn.opencode.companion.util.BuildUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import java.util.concurrent.atomic.AtomicReference

class OpenCodeSettingsConfigurable(private val project: Project) :
    BoundConfigurable("OpenCode Companion") {

    override fun createPanel(): DialogPanel {
        val settings = OpenCodeSettings.getInstance(project)
        return panel {
            group("Executable") {
                val executablePathField = TextFieldWithBrowseButton().apply {
                    addBrowseFolderListener(
                        project,
                        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                            .withTitle("Select OpenCode Executable")
                            .withDescription("Choose the opencode executable file.")
                    )
                }
                run {
                    val executableTextField = executablePathField.textField as? JBTextField ?: return@run
                    val resolvedPath = OpenCodePlugin.getInstance(project).openCodeInfo?.path
                    if (!resolvedPath.isNullOrBlank()) {
                        executableTextField.emptyText.text = resolvedPath
                        return@run
                    }

                    ApplicationManager.getApplication().executeOnPooledThread {
                        val autoResolvedPath = OpenCodeChecker.findExecutable()?.path ?: return@executeOnPooledThread
                        ApplicationManager.getApplication().invokeLater {
                            if (project.isDisposed) return@invokeLater
                            if (executablePathField.text.isBlank()) {
                                executableTextField.emptyText.text = autoResolvedPath
                            }
                        }
                    }
                }
                row("OpenCode Path:") {
                    cell(executablePathField)
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
        val oldSettings = snapshot(settings)

        super.apply()

        val newSettings = snapshot(settings)
        val newPort = newSettings.serverPort
        val newPath = newSettings.executablePath
        val portChanged = newPort != oldSettings.serverPort
        val pathChanged = newPath != oldSettings.executablePath
        val portOrPathChanged = portChanged || pathChanged
        val serverRunning = plugin.isRunning
        val owned = plugin.ownsProcess
        val mustConfirmStop = serverRunning && owned && portOrPathChanged
        val mustReattach = serverRunning && !owned && portChanged


        if (pathChanged) {
            val userProvidedPath = newPath.takeIf { it.isNotBlank() }
            val resolvedRef = AtomicReference<OpenCodeInfo?>()
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    resolvedRef.set(OpenCodeChecker.findExecutable(userProvidedPath))
                },
                "Resolving OpenCode\u2026",
                false,
                project
            )

            val info = resolvedRef.get()
            if (info == null && userProvidedPath != null) {
                restore(settings, oldSettings)
                throw ConfigurationException(
                    "Could not find a valid OpenCode executable. Check the path and try again."
                )
            }

            plugin.openCodeInfo = info
            project.messageBus.syncPublisher(OpenCodeInfoChangedListener.TOPIC)
                .onOpenCodeInfoChanged(info)
        }

        if (mustConfirmStop) {
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
                settings.serverPort = oldSettings.serverPort
                settings.executablePath = oldSettings.executablePath
                reset()
                return
            }

            plugin.stopServer()

        } else if (mustReattach) {
            plugin.reattach(newPort)
        }

        val finalSettings = snapshot(settings)
        if (finalSettings != oldSettings) {
            project.messageBus.syncPublisher(OpenCodeSettingsChangedListener.TOPIC)
                .onSettingsChanged(oldSettings, finalSettings)
        }

        EditorDiffRenderer.getInstance(project).onSettingsChanged()
    }

    private fun restore(settings: OpenCodeSettings, snapshot: OpenCodeSettingsSnapshot) {
        settings.serverPort = snapshot.serverPort
        settings.executablePath = snapshot.executablePath
        settings.inlineDiffEnabled = snapshot.inlineDiffEnabled
        settings.diffTraceEnabled = snapshot.diffTraceEnabled
        settings.diffTraceHistoryEnabled = snapshot.diffTraceHistoryEnabled
        settings.inlineTerminalEnabled = snapshot.inlineTerminalEnabled
        settings.terminalEngine = snapshot.terminalEngine
    }

    private fun snapshot(settings: OpenCodeSettings): OpenCodeSettingsSnapshot = OpenCodeSettingsSnapshot(
        serverPort = settings.serverPort,
        executablePath = settings.executablePath,
        inlineDiffEnabled = settings.inlineDiffEnabled,
        diffTraceEnabled = settings.diffTraceEnabled,
        diffTraceHistoryEnabled = settings.diffTraceHistoryEnabled,
        inlineTerminalEnabled = settings.inlineTerminalEnabled,
        terminalEngine = settings.terminalEngine,
    )
}
