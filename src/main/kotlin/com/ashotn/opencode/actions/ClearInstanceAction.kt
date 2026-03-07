package com.ashotn.opencode.actions

import com.ashotn.opencode.diff.OpenCodeDiffService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Clears the currently selected OpenCode session, removing all diff highlights and hunk markers.
 *
 * Can be constructed with an explicit [project] (for toolbar registration via [setTitleActions])
 * or with no args (for XML registration, where the project is resolved from [AnActionEvent]).
 */
class ClearInstanceAction(private val project: Project? = null) :
    AnAction(ActionStrings.CLEAR_INSTANCE.text, ActionStrings.CLEAR_INSTANCE.description, AllIcons.Actions.GC) {

    override fun update(e: AnActionEvent) {
        val proj = project ?: e.project
        val strings = ActionStrings.CLEAR_INSTANCE
        if (proj == null) {
            e.presentation.isEnabled = false
            e.presentation.text = strings.disabledText
            e.presentation.description = strings.disabledDescription
            return
        }
        val hasSelectedSession = OpenCodeDiffService.getInstance(proj).selectedSessionId() != null
        e.presentation.isEnabled = hasSelectedSession
        e.presentation.text = if (hasSelectedSession) strings.text else strings.disabledText
        e.presentation.description = if (hasSelectedSession) strings.description else strings.disabledDescription
    }

    override fun actionPerformed(e: AnActionEvent) {
        val proj = project ?: e.project ?: return
        OpenCodeDiffService.getInstance(proj).selectSession(null)
    }
}
