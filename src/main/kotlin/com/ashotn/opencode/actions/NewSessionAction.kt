package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.tui.OpenCodeTuiClient
import com.ashotn.opencode.util.applyStrings
import com.ashotn.opencode.util.showNotification
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

class NewSessionAction(private val project: Project) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val running = OpenCodePlugin.getInstance(project).isRunning
        e.presentation.icon = AllIcons.General.Add
        e.applyStrings(ActionStrings.NEW_SESSION, running)
    }

    override fun actionPerformed(e: AnActionEvent) {
        OpenCodeTuiClient.getInstance(project).createSessionAndSelectInTui { success, sessionId, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success && !sessionId.isNullOrBlank()) {
                    project.showNotification(
                        "New session created",
                        "Created and selected session ${sessionId.take(12)} in TUI.",
                        NotificationType.INFORMATION,
                    )
                } else {
                    project.showNotification(
                        "Failed to create session",
                        error ?: "Could not create or select a new OpenCode session.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }
}
