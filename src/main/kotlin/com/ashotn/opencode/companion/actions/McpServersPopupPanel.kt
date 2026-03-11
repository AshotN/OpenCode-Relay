package com.ashotn.opencode.companion.actions

import com.ashotn.opencode.companion.api.config.ConfigApiClient.McpServerConfig
import com.ashotn.opencode.companion.api.mcp.McpApiClient.McpConnectionStatus
import com.ashotn.opencode.companion.api.mcp.McpEntry
import com.ashotn.opencode.companion.api.mcp.McpService
import com.ashotn.opencode.companion.api.transport.ApiResult
import com.ashotn.opencode.companion.ipc.McpChangedListener
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The content panel rendered inside the MCP servers popup.
 * Owns its own load/reload logic so the popup just needs to embed it.
 *
 * Call [subscribeToMcpChanges] after the popup is created, passing the popup's
 * [Disposable] so the subscription is cleaned up when the popup closes.
 */
class McpServersPopupPanel(
    private val port: Int,
    private val mcpService: McpService = McpService(),
) : JPanel(BorderLayout()) {

    private val listPanel = JPanel(GridBagLayout()).apply { isOpaque = false }
    private val statusLabel = JBLabel("Loading…", SwingConstants.CENTER).apply {
        border = JBUI.Borders.empty(8)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private val scrollPane = JBScrollPane(listPanel).apply {
        border = null
        isOpaque = false
        viewport.isOpaque = false
        preferredSize = Dimension(JBUI.scale(360), JBUI.scale(180))
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4)
        add(statusLabel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        loadEntries()
    }

    /**
     * Subscribes to [McpChangedListener] on the project message bus and reloads
     * the list whenever an `mcp.tools.changed` SSE event arrives.
     * The subscription is tied to [popupDisposable] and cleaned up automatically.
     */
    fun subscribeToMcpChanges(project: Project, popupDisposable: Disposable) {
        project.messageBus.connect(popupDisposable).subscribe(
            McpChangedListener.TOPIC,
            McpChangedListener { loadEntries() },
        )
    }

    private fun loadEntries() {
        statusLabel.text = "Loading…"
        statusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        statusLabel.isVisible = true
        listPanel.removeAll()
        listPanel.revalidate()
        listPanel.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = mcpService.listMcpEntries(port)
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is ApiResult.Success -> renderEntries(result.value)
                    is ApiResult.Failure -> showError("Could not load MCP servers: ${result.error}")
                }
            }
        }
    }

    private fun renderEntries(entries: List<McpEntry>) {
        listPanel.removeAll()

        if (entries.isEmpty()) {
            statusLabel.text = "No MCP servers configured."
            statusLabel.isVisible = true
            return
        }

        statusLabel.isVisible = false

        val gbc = GridBagConstraints().apply {
            gridx = 0
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = JBUI.insets(1, 0)
        }

        entries.forEachIndexed { index, entry ->
            gbc.gridy = index
            listPanel.add(buildRow(entry), gbc)
        }

        // Push rows to the top
        gbc.gridy = entries.size
        gbc.weighty = 1.0
        gbc.fill = GridBagConstraints.BOTH
        listPanel.add(Box.createVerticalGlue(), gbc)

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun buildRow(entry: McpEntry): JPanel {
        val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 6)
        }

        // Left: icon + name + type tag (+ optional error line)
        val statusIcon = JBLabel(statusIconFor(entry)).apply {
            toolTipText = statusTooltipFor(entry)
        }

        val nameLabel = JBLabel(entry.name)

        val typeTag = JBLabel(typeTagFor(entry.config)).apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }

        val nameLine = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(nameLabel, BorderLayout.WEST)
            add(typeTag, BorderLayout.CENTER)
        }

        val centerStack = if (entry.connectionError != null) {
            val errorLabel = JBLabel(entry.connectionError).apply {
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                foreground = JBColor.RED
            }
            JPanel(BorderLayout(0, JBUI.scale(1))).apply {
                isOpaque = false
                add(nameLine, BorderLayout.NORTH)
                add(errorLabel, BorderLayout.SOUTH)
            }
        } else {
            nameLine
        }

        val left = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(statusIcon, BorderLayout.WEST)
            add(centerStack, BorderLayout.CENTER)
        }

        row.add(left, BorderLayout.CENTER)
        row.add(buildToggleButton(entry), BorderLayout.EAST)
        return row
    }

    private fun buildToggleButton(entry: McpEntry): JButton {
        val connected = entry.isConnected
        return JButton(if (connected) "Disconnect" else "Connect").apply {
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            isEnabled = !entry.isLoading
            if (entry.isLoading) icon = AnimatedIcon.Default.INSTANCE

            addActionListener {
                isEnabled = false
                icon = AnimatedIcon.Default.INSTANCE

                ApplicationManager.getApplication().executeOnPooledThread {
                    val result = if (connected) mcpService.disconnect(port, entry.name)
                    else mcpService.connect(port, entry.name)

                    ApplicationManager.getApplication().invokeLater {
                        when (result) {
                            is ApiResult.Success -> renderEntries(result.value)
                            // On failure, reload the real server state so the button re-enables
                            // and the entry's status icon/tooltip reflects what actually happened.
                            is ApiResult.Failure -> loadEntries()
                        }
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        statusLabel.text = message
        statusLabel.foreground = JBColor.RED
        statusLabel.isVisible = true
    }

    private fun typeTagFor(config: McpServerConfig) = when (config) {
        is McpServerConfig.Local -> "(local)"
        is McpServerConfig.Remote -> "(remote)"
    }

    private fun statusIconFor(entry: McpEntry) = when {
        entry.isLoading -> AnimatedIcon.Default.INSTANCE
        entry.isConnected -> AllIcons.RunConfigurations.TestPassed
        entry.connectionStatus == McpConnectionStatus.DISABLED -> AllIcons.Actions.Pause
        entry.hasIssue -> AllIcons.RunConfigurations.TestFailed
        else -> AllIcons.RunConfigurations.TestIgnored
    }

    private fun statusTooltipFor(entry: McpEntry) = when {
        entry.isLoading -> "Updating…"
        entry.isConnected -> "Connected"
        entry.connectionStatus == McpConnectionStatus.DISABLED -> "Disabled"
        entry.connectionStatus == McpConnectionStatus.FAILED ->
            "Failed: ${entry.connectionError ?: "unknown error"}"

        entry.connectionStatus == McpConnectionStatus.NEEDS_AUTH -> "Needs authentication"
        entry.connectionStatus == McpConnectionStatus.NEEDS_CLIENT_REGISTRATION ->
            "Needs client registration"

        entry.connectionStatus == null -> "Not yet connected"
        else -> entry.connectionStatus.name.lowercase().replace('_', ' ')
    }
}
