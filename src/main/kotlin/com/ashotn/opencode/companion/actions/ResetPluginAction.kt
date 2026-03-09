package com.ashotn.opencode.companion.actions

import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.ServerState
import com.ashotn.opencode.companion.util.applyStrings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class ResetPluginAction(private val project: Project) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val plugin = OpenCodePlugin.getInstance(project)
        val state = plugin.serverState
        val strings = ActionStrings.RESET_PLUGIN
        e.presentation.icon = AllIcons.Actions.Restart

        if (state == ServerState.RESETTING) {
            e.presentation.isEnabled = false
            e.presentation.text = "Resetting OpenCode..."
            e.presentation.description = "OpenCode reset is in progress"
            return
        }

        val canReset = state == ServerState.READY || state == ServerState.STARTING
        e.applyStrings(strings, canReset)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val plugin = OpenCodePlugin.getInstance(project)
        val state = plugin.serverState
        if (state != ServerState.READY && state != ServerState.STARTING) return
        plugin.resetConnection()
    }
}
