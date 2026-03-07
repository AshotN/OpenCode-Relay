package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.ServerState
import com.ashotn.opencode.util.applyStrings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class StopServerAction(private val project: Project) :
    AnAction(ActionStrings.STOP_SERVER.text, ActionStrings.STOP_SERVER.description, AllIcons.Actions.Suspend) {

    override fun update(e: AnActionEvent) {
        val plugin = OpenCodePlugin.getInstance(project)
        val state = plugin.serverState
        val strings = ActionStrings.STOP_SERVER

        if (state == ServerState.STOPPING) {
            e.presentation.isEnabled = false
            e.presentation.text = "Stopping OpenCode..."
            e.presentation.description = "OpenCode shutdown is in progress"
            return
        }

        val canStop = plugin.ownsProcess && plugin.isRunning
        e.applyStrings(strings, canStop)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val plugin = OpenCodePlugin.getInstance(project)
        if (!plugin.ownsProcess) return
        plugin.stopServer()
    }
}
