package com.ashotn.opencode.toolwindow

import com.ashotn.opencode.actions.OpenBrowserAction
import com.ashotn.opencode.actions.OpenSettingsAction
import com.ashotn.opencode.actions.OpenTerminalAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class OpenCodeToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OpenCodeToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(OpenTerminalAction(project), OpenBrowserAction(project), OpenSettingsAction(project)))
    }
}
