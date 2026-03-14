package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.settings.OpenCodeSettings
import com.ashotn.opencode.relay.util.applyStrings
import com.ashotn.opencode.relay.util.serverUrl
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.net.URI

class OpenBrowserAction(private val project: Project) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val running = OpenCodePlugin.getInstance(project).isRunning
        e.presentation.icon = AllIcons.Toolwindows.WebToolWindow
        e.applyStrings(ActionStrings.OPEN_BROWSER, running)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val port = OpenCodeSettings.getInstance(project).serverPort
        BrowserUtil.browse(URI(serverUrl(port)))
    }
}
