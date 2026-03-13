package com.ashotn.opencode.companion.settings

import com.ashotn.opencode.companion.OpenCodeChecker
import com.ashotn.opencode.companion.OpenCodeInfo
import com.ashotn.opencode.companion.OpenCodeInfoChangedListener
import com.ashotn.opencode.companion.OpenCodePlugin
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

    private val pendingState = OpenCodeSettings.State()

    internal var executableResolver: (String?) -> OpenCodeInfo? = { path -> OpenCodeChecker.findExecutable(path) }

    override fun createPanel(): DialogPanel {
        loadPendingFromPersisted()

        return panel {
            group("Executable") {
                val executablePathField = TextFieldWithBrowseButton().apply {
                    addBrowseFolderListener(
                        project,
                        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                            .withTitle("Select OpenCode Executable")
                            .withDescription("Choose the opencode executable file."),
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
                        .bindText(pendingState::executablePath)
                        .comment("Path to the opencode executable. Leave blank to auto-detect.")
                        .align(AlignX.FILL)
                }
            }
            group("Server") {
                row("Server Port:") {
                    intTextField(1024..65535)
                        .bindIntText(pendingState::serverPort)
                        .comment("Port the OpenCode server listens on (default: 4096)")
                }
            }
            group("Editor") {
                row {
                    checkBox("Show inline diff highlights")
                        .bindSelected(pendingState::inlineDiffEnabled)
                        .comment(
                            "Renders green/red inline diff highlights in the editor " +
                                    "for AI-modified files. Changes take effect immediately.",
                        )
                }
            }
            group("Terminal") {
                val reworkedSupported = BuildUtils.isEmbeddedTerminalSupported
                row {
                    checkBox("Show inline terminal")
                        .bindSelected(pendingState::inlineTerminalEnabled)
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
                                else "Requires IntelliJ 2025.3 or later.",
                            )
                    }
                }.bind(pendingState::terminalEngine)
            }
            group("Diagnostics") {
                row {
                    checkBox("Enable diff trace logging")
                        .bindSelected(pendingState::diffTraceEnabled)
                        .comment(
                            "Writes a JSONL trace file to the system temp directory " +
                                    "(opencode-diff-traces/) for debugging diff pipeline events. " +
                                    "Takes effect after restarting the IDE.",
                        )
                }
                row {
                    checkBox("Include historical diffs in trace")
                        .bindSelected(pendingState::diffTraceHistoryEnabled)
                        .comment(
                            "Also records events from historical (loaded-on-demand) session diffs " +
                                    "in the trace. Only relevant when diff trace logging is enabled. " +
                                    "Takes effect after restarting the IDE.",
                        )
                }
            }
        }
    }

    override fun reset() {
        loadPendingFromPersisted()
        super.reset()
    }

    override fun apply() {
        val settings = OpenCodeSettings.getInstance(project)
        val plugin = OpenCodePlugin.getInstance(project)
        val oldSettings = snapshot(settings.state)
        val oldOpenCodeInfo = plugin.openCodeInfo

        super.apply() // Pushes UI values into pendingState.

        val newSettings = snapshot(pendingState)
        val settingsChanged = newSettings != oldSettings
        val newPort = newSettings.serverPort
        val newPath = newSettings.executablePath
        val portChanged = newPort != oldSettings.serverPort
        val pathChanged = newPath != oldSettings.executablePath
        val shouldResolveInfo = pathChanged || (newPath.isBlank() && oldOpenCodeInfo == null)
        if (!settingsChanged && !shouldResolveInfo) return

        val mustConfirmStop = plugin.isRunning && plugin.ownsProcess && (portChanged || pathChanged)
        val mustReattach = plugin.isRunning && !plugin.ownsProcess && portChanged

        var resolvedInfo = oldOpenCodeInfo
        if (shouldResolveInfo) {
            val userProvidedPath = newPath.takeIf { it.isNotBlank() }
            resolvedInfo = resolveExecutableInfo(userProvidedPath)
            if (userProvidedPath != null && resolvedInfo == null) {
                throw ConfigurationException(
                    "Could not find a valid OpenCode executable. Check the path and try again.",
                )
            }
        }

        if (mustConfirmStop && !confirmStopServerRestart()) {
            reset()
            return
        }

        persistPendingToSettings(settings)

        when {
            mustConfirmStop -> plugin.stopServer()
            mustReattach -> plugin.reattach(newPort)
        }

        if (shouldResolveInfo && resolvedInfo != oldOpenCodeInfo) {
            plugin.openCodeInfo = resolvedInfo
            project.messageBus.syncPublisher(OpenCodeInfoChangedListener.TOPIC)
                .onOpenCodeInfoChanged(resolvedInfo)
        }

        if (settingsChanged) {
            project.messageBus.syncPublisher(OpenCodeSettingsChangedListener.TOPIC)
                .onSettingsChanged(oldSettings, newSettings)
        }

        EditorDiffRenderer.getInstance(project).onSettingsChanged()
    }

    private fun resolveExecutableInfo(userProvidedPath: String?): OpenCodeInfo? {
        val resolvedRef = AtomicReference<OpenCodeInfo?>()
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                resolvedRef.set(executableResolver(userProvidedPath))
            },
            "Resolving OpenCode...",
            false,
            project,
        )
        return resolvedRef.get()
    }

    private fun confirmStopServerRestart(): Boolean =
        Messages.showYesNoDialog(
            project,
            "The OpenCode server is currently running. Applying these changes will stop it. " +
                    "You will need to start it again manually.",
            "Stop OpenCode Server?",
            "Stop Server",
            "Cancel",
            Messages.getWarningIcon(),
        ) == Messages.YES

    private fun loadPendingFromPersisted(settings: OpenCodeSettings = OpenCodeSettings.getInstance(project)) {
        pendingState.serverPort = settings.serverPort
        pendingState.executablePath = settings.executablePath
        pendingState.inlineDiffEnabled = settings.inlineDiffEnabled
        pendingState.diffTraceEnabled = settings.diffTraceEnabled
        pendingState.diffTraceHistoryEnabled = settings.diffTraceHistoryEnabled
        pendingState.inlineTerminalEnabled = settings.inlineTerminalEnabled
        pendingState.terminalEngine = settings.terminalEngine
    }

    private fun persistPendingToSettings(settings: OpenCodeSettings) {
        settings.loadState(pendingState.copy())
    }

    private fun snapshot(state: OpenCodeSettings.State): OpenCodeSettingsSnapshot = OpenCodeSettingsSnapshot(
        serverPort = state.serverPort,
        executablePath = state.executablePath,
        inlineDiffEnabled = state.inlineDiffEnabled,
        diffTraceEnabled = state.diffTraceEnabled,
        diffTraceHistoryEnabled = state.diffTraceHistoryEnabled,
        inlineTerminalEnabled = state.inlineTerminalEnabled,
        terminalEngine = state.terminalEngine,
    )
}
