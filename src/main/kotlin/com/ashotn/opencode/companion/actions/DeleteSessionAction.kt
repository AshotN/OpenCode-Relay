package com.ashotn.opencode.companion.actions

import com.ashotn.opencode.companion.tui.OpenCodeTuiClient
import com.ashotn.opencode.companion.util.applyStrings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnimatedIcon
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deletes the currently selected session.
 *
 * [selectedSessionId] is a lambda that returns the session ID currently selected in the
 * session list, or null if nothing is selected. This allows the toolbar button and the
 * keyboard shortcut to share the same action implementation.
 */
class DeleteSessionAction(
    private val project: Project,
    private val selectedSessionId: () -> String?,
) : AnAction() {

    private val isPending = AtomicBoolean(false)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (isPending.get()) {
            e.presentation.icon = AnimatedIcon.Default.INSTANCE
            e.presentation.isEnabled = false
            e.presentation.text = "Deleting…"
            return
        }
        e.presentation.icon = AllIcons.Actions.GC
        e.applyStrings(ActionStrings.DELETE_SESSION, selectedSessionId() != null)
    }

    override fun actionPerformed(e: AnActionEvent) = perform()

    fun perform() {
        if (!isPending.compareAndSet(false, true)) return
        val sessionId = selectedSessionId()
        if (sessionId == null) {
            isPending.set(false)
            return
        }
        OpenCodeTuiClient.getInstance(project).deleteSession(sessionId) { success, error ->
            isPending.set(false)
            if (!success) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showMessageDialog(
                        project,
                        "Failed to delete session: $error",
                        "Error",
                        Messages.getErrorIcon(),
                    )
                }
            }
        }
    }
}
