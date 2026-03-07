package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.OpenCodeIcons
import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.ServerState
import com.ashotn.opencode.ServerStateListener
import com.ashotn.opencode.actions.OpenBrowserAction
import com.ashotn.opencode.actions.OpenSettingsAction
import com.ashotn.opencode.actions.OpenTerminalAction
import com.ashotn.opencode.actions.StopServerAction
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
        toolWindow.setTitleActions(listOf(OpenTerminalAction(project), OpenBrowserAction(project), StopServerAction(project), OpenSettingsAction(project)))

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
