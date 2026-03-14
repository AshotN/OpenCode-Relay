package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.core.OpenCodeCoreService
import com.ashotn.opencode.relay.util.applyStrings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Clears the currently selected OpenCode session, removing all diff highlights and hunk markers.
 *
 * Can be constructed with an explicit [project] (for toolbar registration via setTitleActions)
 * or with no args (for XML registration, where the project is resolved from [AnActionEvent]).
 */
class ClearSelectedSessionAction(private val project: Project? = null) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val proj = project ?: e.project
        val strings = ActionStrings.CLEAR_SELECTED_SESSION
        e.presentation.icon = AllIcons.Actions.ClearCash
        if (proj == null) {
            e.presentation.isEnabled = false
            e.presentation.text = strings.disabledText
            e.presentation.description = strings.disabledDescription
            return
        }
        val hasSelectedSession = OpenCodeCoreService.getInstance(proj).selectedSessionId() != null
        e.applyStrings(strings, hasSelectedSession)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val proj = project ?: e.project ?: return
        OpenCodeCoreService.getInstance(proj).selectSession(null)
    }
}
