package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.settings.OpenCodeSettings
import com.ashotn.opencode.util.applyStrings
import com.ashotn.opencode.util.serverUrl
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import java.net.URI

class OpenBrowserAction(private val project: Project) :
    AnAction(ActionStrings.OPEN_BROWSER.text, ActionStrings.OPEN_BROWSER.description, AllIcons.Toolwindows.WebToolWindow) {

    override fun update(e: AnActionEvent) {
        val running = OpenCodePlugin.getInstance(project).isRunning
        e.applyStrings(ActionStrings.OPEN_BROWSER, running)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val port = OpenCodeSettings.getInstance(project).serverPort
        BrowserUtil.browse(URI(serverUrl(port)))
    }
}
