package com.ashotn.opencode.relay.toolwindow

import com.ashotn.opencode.relay.OpenCodeIcons
import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.ServerState
import com.ashotn.opencode.relay.ServerStateListener
import com.ashotn.opencode.relay.actions.McpServersAction
import com.ashotn.opencode.relay.actions.OpenBrowserAction
import com.ashotn.opencode.relay.actions.OpenSettingsAction
import com.ashotn.opencode.relay.actions.OpenTerminalAction
import com.ashotn.opencode.relay.actions.ResetPluginAction
import com.ashotn.opencode.relay.actions.StopServerAction
import com.ashotn.opencode.relay.actions.ToggleSessionsSectionAction
import com.intellij.ide.ActivityTracker
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class OpenCodeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OpenCodeToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(ToggleSessionsSectionAction(project), McpServersAction(project), OpenTerminalAction(project), OpenBrowserAction(project), StopServerAction(project), ResetPluginAction(project), OpenSettingsAction(project)))

        // Update tool window icon based on server connection state
        val plugin = OpenCodePlugin.getInstance(project)
        val listener = ServerStateListener { state ->
            ApplicationManager.getApplication().invokeLater {
                toolWindow.setIcon(
                    if (state == ServerState.READY) OpenCodeIcons.Connected
                    else OpenCodeIcons.Disconnected
                )
                ActivityTracker.getInstance().inc()
            }
        }
        plugin.addListener(listener)
        content.setDisposer { plugin.removeListener(listener) }
        // Set initial icon based on current state
        toolWindow.setIcon(
            if (plugin.serverState == ServerState.READY) OpenCodeIcons.Connected else OpenCodeIcons.Disconnected
        )
        ActivityTracker.getInstance().inc()
    }
}
