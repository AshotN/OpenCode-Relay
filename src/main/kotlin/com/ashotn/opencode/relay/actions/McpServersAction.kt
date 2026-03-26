package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.ServerState
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.util.applyStrings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import java.awt.Point

class McpServersAction(private val project: Project) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val ready = OpenCodePlugin.getInstance(project).serverState == ServerState.READY
        e.presentation.icon = AllIcons.Nodes.Plugin
        e.applyStrings(ActionStrings.MCP_SERVERS, ready)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val port = OpenCodeSettings.getInstance(project).serverPort
        val content = McpServersPopupPanel(port)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, content)
            .setTitle("MCP Servers")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .createPopup()

        // Reload the list whenever mcp.tools.changed fires, for as long as the popup is open.
        content.subscribeToMcpChanges(project, popup)

        val component = e.inputEvent?.component
        if (component != null) {
            // Show below the toolbar icon that was clicked
            popup.show(RelativePoint(component, Point(0, component.height)))
        } else {
            popup.showInFocusCenter()
        }
    }
}
