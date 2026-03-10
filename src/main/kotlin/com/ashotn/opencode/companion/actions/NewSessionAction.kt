package com.ashotn.opencode.companion.actions

import com.ashotn.opencode.companion.OpenCodePlugin
import com.ashotn.opencode.companion.tui.OpenCodeTuiClient
import com.ashotn.opencode.companion.util.applyStrings
import com.ashotn.opencode.companion.util.showNotification
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * Creates a new OpenCode session and selects it in the TUI.
 *
 * Can be constructed with an explicit [project] (for toolbar registration via setTitleActions)
 * or with no args (for XML registration, where the project is resolved from [AnActionEvent]).
 */
class NewSessionAction(private val project: Project? = null) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val proj = project ?: e.project
        e.presentation.icon = AllIcons.General.Add
        if (proj == null) {
            e.applyStrings(ActionStrings.NEW_SESSION, false)
            return
        }
        val running = OpenCodePlugin.getInstance(proj).isRunning
        e.applyStrings(ActionStrings.NEW_SESSION, running)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val proj = project ?: e.project ?: return
        OpenCodeTuiClient.getInstance(proj).createSessionAndSelectInTui { success, sessionId, reused, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success && !sessionId.isNullOrBlank()) {
                    if (reused) {
                        proj.showNotification(
                            "Session already empty",
                            "Switched to existing empty session ${sessionId.take(12)}.",
                            NotificationType.INFORMATION,
                        )
                    } else {
                        proj.showNotification(
                            "New session created",
                            "Created and selected session ${sessionId.take(12)} in TUI.",
                            NotificationType.INFORMATION,
                        )
                    }
                } else {
                    proj.showNotification(
                        "Failed to create session",
                        error ?: "Could not create or select a new OpenCode session.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }
}
