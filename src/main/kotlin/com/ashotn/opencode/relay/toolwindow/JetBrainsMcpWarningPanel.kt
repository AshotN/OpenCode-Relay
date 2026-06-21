package com.ashotn.opencode.relay.toolwindow

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.ServerState
import com.ashotn.opencode.relay.ServerStateListener
import com.ashotn.opencode.relay.api.config.ConfigApiClient.McpServerConfig
import com.ashotn.opencode.relay.api.mcp.McpEntry
import com.ashotn.opencode.relay.api.mcp.McpService
import com.ashotn.opencode.relay.api.transport.ApiResult
import com.ashotn.opencode.relay.ipc.McpChangedListener
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.settings.OpenCodeSettingsChangedListener
import com.ashotn.opencode.relay.settings.snapshot
import com.ashotn.opencode.relay.util.JetBrainsMcpDetector
import com.ashotn.opencode.relay.util.JetBrainsMcpStatus
import com.intellij.ide.BrowserUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.UIManager

class JetBrainsMcpWarningPanel(
    private val project: Project,
    private val mcpService: McpService = McpService.forProject(project),
) : JPanel(BorderLayout(JBUI.scale(8), 0)), Disposable {

    private companion object {
        private const val JETBRAINS_MCP_README_URL = "https://github.com/AshotN/OpenCode-Relay#jetbrains-mcp"
    }

    private data class WarningState(
        val key: String,
        val message: String,
        val showJetBrainsSettingsAction: Boolean,
    )

    private val settings = OpenCodeSettings.getInstance(project)
    private val plugin = OpenCodePlugin.getInstance(project)
    private val requestId = AtomicInteger()
    private var dismissedWarningKey: String? = null
    private var currentWarningKey: String? = null
    private var disposed = false

    private val iconLabel = JBLabel().apply {
        icon = AllIcons.General.Warning
    }
    private val messageLabel = JTextArea().apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        minimumSize = Dimension(0, 0)
        font = UIManager.getFont("Label.font")
        foreground = JBColor.foreground()
    }
    private val messagePanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        minimumSize = Dimension(0, 0)
        add(iconLabel, BorderLayout.WEST)
        add(messageLabel, BorderLayout.CENTER)
    }
    private val openJetBrainsSettingsButton = actionButton("Open Settings") { _ ->
        BrowserUtil.browse("jetbrains://idea/settings?name=Tools--MCP+Server")
    }
    private val setupGuideButton = actionButton("Setup Guide") { _ ->
        BrowserUtil.browse(JETBRAINS_MCP_README_URL)
    }
    private val dismissButton = actionButton("Dismiss") { _ ->
        dismissedWarningKey = currentWarningKey
        isVisible = false
    }
    private val dontShowAgainButton = actionButton("Don't show again") { _ ->
        val oldSettings = settings.snapshot()
        settings.jetBrainsMcpWarningEnabled = false
        val newSettings = settings.snapshot()
        if (oldSettings != newSettings) {
            project.messageBus.syncPublisher(OpenCodeSettingsChangedListener.TOPIC)
                .onSettingsChanged(oldSettings, newSettings)
        }
        isVisible = false
    }
    private val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
        isOpaque = false
        add(openJetBrainsSettingsButton)
        add(setupGuideButton)
        add(dismissButton)
        add(dontShowAgainButton)
    }
    private val serverStateListener = ServerStateListener { refresh() }

    init {
        isVisible = false
        isOpaque = true
        background = JBColor.namedColor("Editor.Notification.background", JBColor(0xFFF8D8, 0x4D3D19))
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(6, 8),
        )

        add(messagePanel, BorderLayout.CENTER)
        add(actionsPanel, BorderLayout.EAST)

        plugin.addListener(serverStateListener)
        project.messageBus.connect(this).subscribe(
            OpenCodeSettingsChangedListener.TOPIC,
            OpenCodeSettingsChangedListener { _, _ -> refresh() },
        )
        project.messageBus.connect(this).subscribe(
            McpChangedListener.TOPIC,
            McpChangedListener { refresh() },
        )

        refresh()
    }

    fun refresh() {
        if (disposed || project.isDisposed) return
        val id = requestId.incrementAndGet()
        ApplicationManager.getApplication().executeOnPooledThread {
            val warning = computeWarning()
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || id != requestId.get()) return@invokeLater
                applyWarning(warning)
            }
        }
    }

    private fun computeWarning(): WarningState? {
        if (!settings.jetBrainsMcpWarningEnabled) return null

        return when (val jetBrainsMcpStatus = JetBrainsMcpDetector.status()) {
            JetBrainsMcpStatus.Unknown -> null
            is JetBrainsMcpStatus.Available -> computeAvailableWarning(jetBrainsMcpStatus)
        }
    }

    private fun computeAvailableWarning(jetBrainsMcpStatus: JetBrainsMcpStatus.Available): WarningState? {
        if (!jetBrainsMcpStatus.enabled) {
            return WarningState(
                key = "jetbrains-mcp-disabled",
                message = "JetBrains MCP Server is disabled. Enable it to give OpenCode IDE tools.",
                showJetBrainsSettingsAction = true,
            )
        }

        if (plugin.serverState != ServerState.READY) return null

        val openCodeMcpEntries = when (val result = mcpService.listMcpEntries(settings.serverPort)) {
            is ApiResult.Success -> result.value
            is ApiResult.Failure -> return null
        }

        if (openCodeMcpEntries.any { it.matchesJetBrainsMcp(jetBrainsMcpStatus) && it.isConnected }) return null

        return WarningState(
            key = "opencode-mcp-not-connected",
            message = "OpenCode is running, but it is not connected to JetBrains MCP. Configure JetBrains MCP in opencode.json and restart OpenCode.",
            showJetBrainsSettingsAction = false,
        )
    }

    private fun McpEntry.matchesJetBrainsMcp(jetBrainsMcpStatus: JetBrainsMcpStatus.Available): Boolean {
        val remoteConfig = config as? McpServerConfig.Remote ?: return false
        return remoteConfig.url.matchesJetBrainsMcpUrl(jetBrainsMcpStatus)
    }

    private fun String.matchesJetBrainsMcpUrl(jetBrainsMcpStatus: JetBrainsMcpStatus.Available): Boolean {
        val remoteUri = toUriOrNull() ?: return false
        val expectedUris = listOfNotNull(jetBrainsMcpStatus.sseUrl, jetBrainsMcpStatus.streamUrl)
            .mapNotNull(String::toUriOrNull)
        if (expectedUris.any { remoteUri.sameJetBrainsEndpoint(it) }) return true

        val port = jetBrainsMcpStatus.port ?: return false
        return remoteUri.isLocalHost() && remoteUri.port == port && remoteUri.normalizedPath() in setOf("/sse", "/mcp")
    }

    private fun applyWarning(warning: WarningState?) {
        currentWarningKey = warning?.key
        if (warning == null) {
            dismissedWarningKey = null
            isVisible = false
            return
        }
        if (warning.key == dismissedWarningKey) {
            isVisible = false
            return
        }

        messageLabel.text = warning.message
        openJetBrainsSettingsButton.isVisible = warning.showJetBrainsSettingsAction
        isVisible = true
        revalidate()
        repaint()
    }

    override fun dispose() {
        disposed = true
        plugin.removeListener(serverStateListener)
    }

    private fun actionButton(text: String, action: (JButton) -> Unit): JButton = JButton(text).apply {
        isFocusable = false
        addActionListener { action(this) }
    }
}

private fun String.toUriOrNull(): URI? = runCatching { URI(this) }.getOrNull()

private fun URI.sameJetBrainsEndpoint(other: URI): Boolean =
    compatibleScheme(other) && compatibleLocalHost(other) && port == other.port && normalizedPath() == other.normalizedPath()

private fun URI.compatibleScheme(other: URI): Boolean =
    scheme.isNullOrBlank() || other.scheme.isNullOrBlank() || scheme.equals(other.scheme, ignoreCase = true)

private fun URI.compatibleLocalHost(other: URI): Boolean =
    host.equals(other.host, ignoreCase = true) || (isLocalHost() && other.isLocalHost())

private fun URI.isLocalHost(): Boolean = host.orEmpty().lowercase() in setOf("", "localhost", "127.0.0.1", "::1")

private fun URI.normalizedPath(): String = path.orEmpty().trimEnd('/').ifEmpty { "/" }
