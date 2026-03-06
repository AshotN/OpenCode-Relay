package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.OpenCodeChecker
import com.ashotn.opencode.OpenCodeInfo
import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.ServerState
import com.ashotn.opencode.ServerStateListener
import com.ashotn.opencode.settings.OpenCodeSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

class InstalledPanel(private val project: Project, parentDisposable: Disposable) :
    JPanel(BorderLayout()), Disposable, ServerStateListener {

    companion object {
        private const val CARD_START = "start"
        private const val CARD_STOP = "stop"
    }

    private val portStatusLabel = JBLabel("Checking…", SwingConstants.CENTER)
    private val startButton = JButton("Start OpenCode", AllIcons.Actions.Execute)
    private val stopButton = JButton("Stop OpenCode", AllIcons.Actions.Suspend)
    private val buttonCardLayout = CardLayout()
    private val buttonPanel = JPanel(buttonCardLayout).apply {
        isOpaque = false
        add(startButton, CARD_START)
        add(stopButton, CARD_STOP)
    }

    private val plugin = OpenCodePlugin.getInstance(project)
    private val settings = OpenCodeSettings.getInstance(project)
    private val resolvedExecutableInfo: OpenCodeInfo =
        OpenCodeChecker.findExecutable(settings.executablePath.takeIf { it.isNotBlank() })
            ?: OpenCodeInfo(settings.executablePath, "")

    init {
        val base = UIUtil.getLabelFont()
        val pad = JBUI.scale(16)

        // --- Center: port status + buttons ---
        val centerPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints().apply {
                gridx = 0; fill = GridBagConstraints.NONE; anchor = GridBagConstraints.CENTER
                insets = Insets(JBUI.scale(4), pad, JBUI.scale(4), pad)
            }

            portStatusLabel.font = base.deriveFont(base.size * 1.1f)

            gbc.gridy = 0; add(portStatusLabel, gbc)
            gbc.gridy = 1; gbc.insets = Insets(JBUI.scale(12), pad, JBUI.scale(4), pad)
            add(buttonPanel, gbc)
        }

        // --- Bottom: subtitle + path ---
        val bottomPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints().apply {
                gridx = 0; fill = GridBagConstraints.HORIZONTAL
                insets = Insets(JBUI.scale(4), pad, JBUI.scale(4), pad)
            }

            val versionSuffix = if (resolvedExecutableInfo.version.isNotBlank()) "(${resolvedExecutableInfo.version})" else ""
            val subtitleLabel = JBLabel("OpenCode$versionSuffix is installed and ready to use.", SwingConstants.CENTER).apply {
                font = base.deriveFont(base.size * 1.1f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }
            val pathLabel = JBLabel(resolvedExecutableInfo.path, SwingConstants.CENTER).apply {
                font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
            }

            gbc.gridy = 0; add(subtitleLabel, gbc)
            gbc.gridy = 1; gbc.insets = Insets(JBUI.scale(2), pad, JBUI.scale(16), pad)
            add(pathLabel, gbc)
        }

        add(centerPanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        startButton.addActionListener {
            buttonCardLayout.show(buttonPanel, CARD_STOP)
            stopButton.isEnabled = true
            portStatusLabel.text = "Starting…"
            portStatusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            plugin.startServer(settings.serverPort, resolvedExecutableInfo.path)
        }
        stopButton.addActionListener {
            stopButton.isEnabled = false
            portStatusLabel.text = "Stopping…"
            portStatusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            plugin.stopServer()
        }

        plugin.addListener(this)
        plugin.checkPort(settings.serverPort)
        plugin.startPolling(settings.serverPort)

        Disposer.register(parentDisposable, this)
    }

    override fun onStateChanged(state: ServerState) {
        val port = settings.serverPort
        when (state) {
            ServerState.UNKNOWN -> {
                portStatusLabel.text = "Checking…"
                portStatusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                buttonPanel.isVisible = false
            }
            ServerState.STARTING -> {
                portStatusLabel.text = "Starting on port $port…"
                portStatusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                stopButton.isEnabled = true
                buttonCardLayout.show(buttonPanel, CARD_STOP)
                buttonPanel.isVisible = true
            }
            ServerState.READY -> {
                portStatusLabel.text = "OpenCode is running on port $port"
                portStatusLabel.foreground = JBUI.CurrentTheme.Label.foreground()
                val owned = plugin.ownsProcess
                if (owned) {
                    stopButton.isEnabled = true
                    buttonCardLayout.show(buttonPanel, CARD_STOP)
                }
                buttonPanel.isVisible = owned
            }
            ServerState.STOPPED -> {
                portStatusLabel.text = "OpenCode is not running on port $port"
                portStatusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                startButton.isEnabled = true
                buttonCardLayout.show(buttonPanel, CARD_START)
                buttonPanel.isVisible = true
            }
            ServerState.PORT_CONFLICT -> {
                portStatusLabel.text = "Port $port is in use by another process"
                portStatusLabel.foreground = JBUI.CurrentTheme.Label.disabledForeground()
                buttonPanel.isVisible = false
            }
        }
    }

    override fun dispose() {
        plugin.removeListener(this)
    }
}
