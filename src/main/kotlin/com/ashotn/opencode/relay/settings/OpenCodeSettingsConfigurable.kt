package com.ashotn.opencode.relay.settings

import com.ashotn.opencode.relay.OpenCodeChecker
import com.ashotn.opencode.relay.OpenCodeExecutableResolutionState
import com.ashotn.opencode.relay.OpenCodeInfo
import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.core.EditorDiffRenderer
import com.ashotn.opencode.relay.settings.OpenCodeSettings.TerminalEngine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.text.JTextComponent

class OpenCodeSettingsConfigurable(private val project: Project) :
    BoundConfigurable("OpenCode Relay") {

    internal data class CorsOriginRow(var origin: String = "")

    private data class EditableTablePanel<T>(
        val table: TableView<T>,
        val panel: JComponent,
    )

    private val pendingState = OpenCodeSettings.State()
    internal lateinit var serverCorsOriginsModel: ListTableModel<CorsOriginRow>
    internal lateinit var serverEnvironmentVariablesModel: ListTableModel<OpenCodeSettings.EnvironmentVariable>
    internal lateinit var serverCorsOriginsTable: TableView<CorsOriginRow>
    internal lateinit var serverEnvironmentVariablesTable: TableView<OpenCodeSettings.EnvironmentVariable>

    internal lateinit var serverHostnameField: JBTextField
    internal lateinit var serverAuthUsernameField: JBTextField
    internal lateinit var serverAuthPasswordField: JBPasswordField
    internal lateinit var protectPluginLaunchedServerWithAuthCheckBox: JCheckBox
    internal lateinit var serverMdnsEnabledCheckBox: JCheckBox
    internal lateinit var serverMdnsDomainField: JBTextField

    internal var executableResolver: (String?) -> OpenCodeInfo? = { path -> OpenCodeChecker.findExecutable(path) }

    override fun createPanel(): DialogPanel {
        loadPendingFromPersisted()

        serverCorsOriginsModel = ListTableModel(corsOriginColumn())
        val serverCorsOriginsEditor = createEditableTablePanel(
            model = serverCorsOriginsModel,
            preferredHeight = 110,
            onAddRow = ::CorsOriginRow,
        )
        serverCorsOriginsTable = serverCorsOriginsEditor.table
        serverEnvironmentVariablesModel =
            ListTableModel(environmentVariableNameColumn(), environmentVariableValueColumn())
        val serverEnvironmentVariablesEditor = createEditableTablePanel(
            model = serverEnvironmentVariablesModel,
            preferredHeight = 140,
            onAddRow = ::environmentVariableRow,
        )
        serverEnvironmentVariablesTable = serverEnvironmentVariablesEditor.table

        syncServerCorsOriginsModel(pendingState.serverCorsOrigins)
        syncServerEnvironmentVariablesModel(pendingState.serverEnvironmentVariables)
        resetServerAuthPasswordField()

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
                row("Port:") {
                    intTextField(1024..65535)
                        .bindIntText(pendingState::serverPort)
                        .comment("`--port`: Port to listen on. Default: 4096")
                }
                row("Hostname:") {
                    val cell = textField()
                        .bindText(pendingState::serverHostname)
                        .comment("`--hostname`: Hostname to listen on. Default: 127.0.0.1")
                        .align(AlignX.FILL)
                    serverHostnameField = cell.component
                }
                row("mDNS:") {
                    val cell = checkBox("Enable discovery")
                        .bindSelected(pendingState::serverMdnsEnabled)
                        .comment("`--mdns`: Enable mDNS discovery. Default: false")
                    serverMdnsEnabledCheckBox = cell.component
                }
                row("mDNS Domain:") {
                    val cell = textField()
                        .bindText(pendingState::serverMdnsDomain)
                        .comment("`--mdns-domain`: Custom domain name for mDNS service. Default: opencode.local")
                        .align(AlignX.FILL)
                    serverMdnsDomainField = cell.component
                }
                row("CORS Origins:") {
                    cell(serverCorsOriginsEditor.panel)
                        .comment("`--cors`: Additional browser origins to allow. Default: []. Use Add and Remove to manage entries, and edit values inline.")
                        .align(AlignX.FILL)
                        .resizableColumn()
                }
            }
            group("Server Authentication") {
                row("Username:") {
                    val cell = textField()
                        .bindText(pendingState::serverAuthUsername)
                        .comment("Used for protected OpenCode server connections. Default: opencode")
                        .align(AlignX.FILL)
                    serverAuthUsernameField = cell.component
                }
                row("Password:") {
                    val field = JBPasswordField()
                    serverAuthPasswordField = field
                    cell(field)
                        .comment("Stored securely in the IDE password safe.")
                        .align(AlignX.FILL)
                }
                row {
                    val cell = checkBox("Protect server launched by plugin")
                        .bindSelected(pendingState::protectPluginLaunchedServerWithAuth)
                        .comment("When enabled, the plugin will use these credentials for plugin-launched OpenCode servers.")
                    protectPluginLaunchedServerWithAuthCheckBox = cell.component
                }
            }
            group("Environment") {
                row("Variables:") {
                    cell(serverEnvironmentVariablesEditor.panel)
                        .comment("Additional environment variables passed to OpenCode processes launched by the plugin. `OPENCODE_SERVER_USERNAME` and `OPENCODE_SERVER_PASSWORD` are managed above.")
                        .align(AlignX.FILL)
                        .resizableColumn()
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
                row {
                    checkBox("Show inline terminal")
                        .bindSelected(pendingState::inlineTerminalEnabled)
                        .comment("Embeds the OpenCode TUI directly inside the tool window panel when the server is running.")
                }
                buttonsGroup("Terminal engine:") {
                    row {
                        radioButton("Classic (Recommended)", TerminalEngine.CLASSIC)
                            .comment("Legacy JediTerm widget.")
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
        cancelTableEdits()
        loadPendingFromPersisted()
        syncServerCorsOriginsModel(pendingState.serverCorsOrigins)
        syncServerEnvironmentVariablesModel(pendingState.serverEnvironmentVariables)
        super.reset()
        resetServerAuthPasswordField()
    }

    override fun isModified(): Boolean {
        val settings = OpenCodeSettings.getInstance(project)
        return super.isModified() ||
                serializeServerCorsOrigins() != settings.serverCorsOrigins ||
                serializeServerEnvironmentVariables() != settings.serverEnvironmentVariables ||
                currentServerAuthPassword() != OpenCodeServerAuth.getInstance(project).password()
    }

    override fun apply() {
        val settings = OpenCodeSettings.getInstance(project)
        val serverAuth = OpenCodeServerAuth.getInstance(project)
        val plugin = OpenCodePlugin.getInstance(project)
        val oldSettings = snapshot(settings.state)
        val oldPassword = serverAuth.password()
        val oldResolutionState = plugin.executableResolutionState

        commitTableEdits()
        super.apply() // Pushes UI values into pendingState.
        pendingState.serverCorsOrigins = serializeServerCorsOrigins()
        pendingState.serverAuthUsername =
            pendingState.serverAuthUsername.trim().ifEmpty { OpenCodeSettings.DEFAULT_SERVER_AUTH_USERNAME }
        pendingState.serverEnvironmentVariables = serializeServerEnvironmentVariables()

        if (pendingState.serverEnvironmentVariables.any { it.name.contains('=') }) {
            throw ConfigurationException("Environment variable names cannot contain '='")
        }
        validateReservedServerAuthEnvironmentVariables(pendingState.serverEnvironmentVariables)
        if (pendingState.protectPluginLaunchedServerWithAuth && currentServerAuthPassword().isBlank()) {
            throw ConfigurationException("Enter a password to protect the server launched by plugin")
        }

        val newSettings = snapshot(pendingState)
        val settingsChanged = newSettings != oldSettings
        val newPassword = currentServerAuthPassword()
        val passwordChanged = newPassword != oldPassword
        val connectionAuthChanged = oldSettings.serverAuthUsername != newSettings.serverAuthUsername || passwordChanged
        val oldLaunchAuthEnabled = oldSettings.protectPluginLaunchedServerWithAuth && oldPassword.isNotEmpty()
        val newLaunchAuthEnabled = newSettings.protectPluginLaunchedServerWithAuth && newPassword.isNotEmpty()
        val launchAuthChanged =
            oldLaunchAuthEnabled != newLaunchAuthEnabled ||
                    (newLaunchAuthEnabled && connectionAuthChanged)
        val newPort = newSettings.serverPort
        val newPath = newSettings.executablePath
        val portChanged = newPort != oldSettings.serverPort
        val pathChanged = newPath != oldSettings.executablePath
        val shouldUpdateExecutableResolution =
            pathChanged || (newPath.isBlank() && oldResolutionState == OpenCodeExecutableResolutionState.Resolving)
        if (!settingsChanged && !passwordChanged && !shouldUpdateExecutableResolution) return

        val mustConfirmStop = plugin.isRunning && plugin.ownsProcess && (portChanged || pathChanged || launchAuthChanged)
        val mustReattach = plugin.isRunning && !plugin.ownsProcess && portChanged
        val mustResetConnection = plugin.isRunning && !plugin.ownsProcess && !portChanged && connectionAuthChanged

        var resolvedState = oldResolutionState
        if (shouldUpdateExecutableResolution) {
            val userProvidedPath = newPath.takeIf { it.isNotBlank() }
            val detectedExecutableInfo = detectExecutableInfo(userProvidedPath)
            if (userProvidedPath != null && detectedExecutableInfo == null) {
                throw ConfigurationException(
                    "Could not find a valid OpenCode executable. Check the path and try again.",
                )
            }
            resolvedState =
                detectedExecutableInfo?.let(OpenCodeExecutableResolutionState::Resolved)
                    ?: OpenCodeExecutableResolutionState.NotFound
        }

        if (mustConfirmStop && !confirmStopServerRestart()) {
            reset()
            return
        }

        persistPendingToSettings(settings)
        if (passwordChanged) {
            serverAuth.setPassword(newPassword)
        }

        when {
            mustConfirmStop -> plugin.stopServer()
            mustReattach -> plugin.reattach(newPort)
            mustResetConnection -> plugin.resetConnection()
        }

        if (connectionAuthChanged && !plugin.ownsProcess && !mustReattach && !mustResetConnection) {
            plugin.checkPort(newPort)
        }

        if (shouldUpdateExecutableResolution && resolvedState != oldResolutionState) {
            plugin.setExecutableResolutionState(resolvedState)
        }

        if (settingsChanged) {
            project.messageBus.syncPublisher(OpenCodeSettingsChangedListener.TOPIC)
                .onSettingsChanged(oldSettings, newSettings)
        }

        EditorDiffRenderer.getInstance(project).onSettingsChanged()
    }

    private fun detectExecutableInfo(userProvidedPath: String?): OpenCodeInfo? {
        val detectedInfoRef = AtomicReference<OpenCodeInfo?>()
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                detectedInfoRef.set(executableResolver(userProvidedPath))
            },
            "Resolving OpenCode...",
            false,
            project,
        )
        return detectedInfoRef.get()
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
        pendingState.serverHostname = settings.serverHostname
        pendingState.serverMdnsEnabled = settings.serverMdnsEnabled
        pendingState.serverMdnsDomain = settings.serverMdnsDomain
        pendingState.serverCorsOrigins = settings.serverCorsOrigins
        pendingState.serverAuthUsername = settings.serverAuthUsername
        pendingState.protectPluginLaunchedServerWithAuth = settings.protectPluginLaunchedServerWithAuth
        pendingState.serverEnvironmentVariables = settings.serverEnvironmentVariables.map { it.copy() }.toMutableList()
        pendingState.executablePath = settings.executablePath
        pendingState.inlineDiffEnabled = settings.inlineDiffEnabled
        pendingState.diffTraceEnabled = settings.diffTraceEnabled
        pendingState.diffTraceHistoryEnabled = settings.diffTraceHistoryEnabled
        pendingState.inlineTerminalEnabled = settings.inlineTerminalEnabled
        pendingState.sessionsSectionVisible = settings.sessionsSectionVisible
        pendingState.terminalEngine =
            if (settings.terminalEngine == TerminalEngine.REWORKED) TerminalEngine.CLASSIC else settings.terminalEngine
    }

    private fun syncServerCorsOriginsModel(serializedOrigins: String) {
        if (!::serverCorsOriginsModel.isInitialized) return
        serverCorsOriginsModel.setItems(
            serializedOrigins.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map(::CorsOriginRow)
                .toList(),
        )
    }

    private fun serializeServerCorsOrigins(): String =
        currentCorsOriginRows()
            .map { it.origin.trim() }
            .filter(String::isNotEmpty)
            .joinToString("\n")

    private fun syncServerEnvironmentVariablesModel(entries: List<OpenCodeSettings.EnvironmentVariable>) {
        if (!::serverEnvironmentVariablesModel.isInitialized) return
        serverEnvironmentVariablesModel.setItems(entries.map { it.copy() })
    }

    private fun resetServerAuthPasswordField() {
        if (!::serverAuthPasswordField.isInitialized) return
        serverAuthPasswordField.text = OpenCodeServerAuth.getInstance(project).password()
    }

    private fun currentServerAuthPassword(): String =
        if (::serverAuthPasswordField.isInitialized) String(serverAuthPasswordField.password) else ""

    private fun serializeServerEnvironmentVariables(): MutableList<OpenCodeSettings.EnvironmentVariable> =
        currentEnvironmentVariables()
            .map { it.copy(name = it.name.trim()) }
            .filter { it.name.isNotEmpty() }
            .toMutableList()

    private fun validateReservedServerAuthEnvironmentVariables(
        environmentVariables: List<OpenCodeSettings.EnvironmentVariable>,
    ) {
        val reservedNames = setOf("OPENCODE_SERVER_USERNAME", "OPENCODE_SERVER_PASSWORD")
        val reservedName = environmentVariables
            .firstOrNull { it.name.uppercase() in reservedNames }
            ?.name
            ?: return
        throw ConfigurationException(
            "Use the Server Authentication fields instead of $reservedName",
        )
    }

    private fun currentCorsOriginRows(): List<CorsOriginRow> =
        currentTableItems(
            table = serverCorsOriginsTable,
            model = serverCorsOriginsModel,
            copyItem = CorsOriginRow::copy,
        ) { item, _, value ->
            item.origin = value
        }

    private fun currentEnvironmentVariables(): List<OpenCodeSettings.EnvironmentVariable> =
        currentTableItems(
            table = serverEnvironmentVariablesTable,
            model = serverEnvironmentVariablesModel,
            copyItem = OpenCodeSettings.EnvironmentVariable::copy,
        ) { item, column, value ->
            if (column == 0) item.name = value else item.value = value
        }

    private fun <T> currentTableItems(
        table: TableView<T>,
        model: ListTableModel<T>,
        copyItem: (T) -> T,
        applyEditorValue: (item: T, column: Int, value: String) -> Unit,
    ): List<T> {
        val items = model.items.map(copyItem).toMutableList()
        if (!table.isEditing) return items

        val editingRow = table.editingRow
        val editingColumn = table.editingColumn
        val editorValue = (table.editorComponent as? JTextComponent)?.text ?: return items
        if (editingRow !in items.indices || editingColumn < 0) return items

        applyEditorValue(items[editingRow], editingColumn, editorValue)
        return items
    }

    private fun commitTableEdits() {
        stopCellEditing(serverCorsOriginsTable)
        stopCellEditing(serverEnvironmentVariablesTable)
    }

    private fun cancelTableEdits() {
        cancelCellEditing(serverCorsOriginsTable)
        cancelCellEditing(serverEnvironmentVariablesTable)
    }

    private fun stopCellEditing(table: TableView<*>) {
        if (table.isEditing) {
            table.cellEditor?.stopCellEditing()
        }
    }

    private fun cancelCellEditing(table: TableView<*>) {
        if (table.isEditing) {
            table.cellEditor?.cancelCellEditing()
        }
    }

    private fun persistPendingToSettings(settings: OpenCodeSettings) {
        settings.loadState(pendingState.copy())
    }

    private fun snapshot(state: OpenCodeSettings.State): OpenCodeSettingsSnapshot = OpenCodeSettingsSnapshot(
        serverPort = state.serverPort,
        serverHostname = state.serverHostname,
        serverMdnsEnabled = state.serverMdnsEnabled,
        serverMdnsDomain = state.serverMdnsDomain,
        serverCorsOrigins = state.serverCorsOrigins,
        serverAuthUsername = state.serverAuthUsername,
        protectPluginLaunchedServerWithAuth = state.protectPluginLaunchedServerWithAuth,
        serverEnvironmentVariables = state.serverEnvironmentVariables.map { it.copy() },
        executablePath = state.executablePath,
        inlineDiffEnabled = state.inlineDiffEnabled,
        diffTraceEnabled = state.diffTraceEnabled,
        diffTraceHistoryEnabled = state.diffTraceHistoryEnabled,
        inlineTerminalEnabled = state.inlineTerminalEnabled,
        sessionsSectionVisible = state.sessionsSectionVisible,
        terminalEngine = state.terminalEngine,
    )

    private fun corsOriginColumn(): ColumnInfo<CorsOriginRow, String> =
        object : ColumnInfo<CorsOriginRow, String>("Origin") {
            override fun valueOf(item: CorsOriginRow): String = item.origin

            override fun isCellEditable(item: CorsOriginRow): Boolean = true

            override fun setValue(item: CorsOriginRow, value: String?) {
                item.origin = value.orEmpty()
            }
        }

    private fun environmentVariableNameColumn(): ColumnInfo<OpenCodeSettings.EnvironmentVariable, String> =
        object : ColumnInfo<OpenCodeSettings.EnvironmentVariable, String>("Name") {
            override fun valueOf(item: OpenCodeSettings.EnvironmentVariable): String = item.name

            override fun isCellEditable(item: OpenCodeSettings.EnvironmentVariable): Boolean = true

            override fun setValue(item: OpenCodeSettings.EnvironmentVariable, value: String?) {
                item.name = value.orEmpty()
            }
        }

    private fun environmentVariableValueColumn(): ColumnInfo<OpenCodeSettings.EnvironmentVariable, String> =
        object : ColumnInfo<OpenCodeSettings.EnvironmentVariable, String>("Value") {
            override fun valueOf(item: OpenCodeSettings.EnvironmentVariable): String = item.value

            override fun isCellEditable(item: OpenCodeSettings.EnvironmentVariable): Boolean = true

            override fun setValue(item: OpenCodeSettings.EnvironmentVariable, value: String?) {
                item.value = value.orEmpty()
            }
        }

    private fun environmentVariableRow(): OpenCodeSettings.EnvironmentVariable = OpenCodeSettings.EnvironmentVariable()

    private fun <T> createEditableTablePanel(
        model: ListTableModel<T>,
        preferredHeight: Int,
        onAddRow: () -> T,
    ): EditableTablePanel<T> {
        val table = TableView(model).apply {
            setShowGrid(false)
            intercellSpacing = JBUI.emptySize()
            tableHeader.reorderingAllowed = false
            rowHeight = JBUI.scale(24)
        }

        val panel = ToolbarDecorator.createDecorator(table)
            .disableUpDownActions()
            .setAddAction {
                val updatedItems = model.items.toMutableList().apply {
                    add(onAddRow())
                }
                model.setItems(updatedItems)
                val row = updatedItems.lastIndex
                if (row >= 0) {
                    table.selectionModel.setSelectionInterval(row, row)
                    table.editCellAt(row, 0)
                    table.editorComponent?.requestFocusInWindow()
                }
            }
            .setRemoveAction {
                val selectedRow = table.selectedRow
                if (selectedRow >= 0) {
                    val updatedItems = model.items.toMutableList()
                    updatedItems.removeAt(selectedRow)
                    model.setItems(updatedItems)
                }
            }
            .createPanel().apply {
                minimumSize = JBUI.size(0, JBUI.scale(preferredHeight))
                preferredSize = JBUI.size(0, JBUI.scale(preferredHeight))
            }

        return EditableTablePanel(table = table, panel = panel)
    }
}
