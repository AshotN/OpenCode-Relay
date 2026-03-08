package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.ServerState
import com.ashotn.opencode.util.applyStrings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

class ResetPluginAction(private val project: Project) :
    AnAction(ActionStrings.RESET_PLUGIN.text, ActionStrings.RESET_PLUGIN.description, AllIcons.Actions.Restart) {

    override fun update(e: AnActionEvent) {
        val plugin = OpenCodePlugin.getInstance(project)
        val state = plugin.serverState
        val strings = ActionStrings.RESET_PLUGIN

        if (state == ServerState.STOPPING) {
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
