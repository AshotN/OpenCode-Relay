package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.tui.OpenCodeTuiClient
import com.ashotn.opencode.relay.util.applyStrings
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
 * Renames the currently selected session.
 *
 * [selectedSession] is a lambda that returns a (sessionId, currentTitle) pair for the session
 * currently selected in the session list, or null if nothing is selected.
 */
class RenameSessionAction(
    private val project: Project,
    private val selectedSession: () -> Pair<String, String>?,
) : AnAction() {

    private val isPending = AtomicBoolean(false)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        if (isPending.get()) {
            e.presentation.icon = AnimatedIcon.Default.INSTANCE
            e.presentation.isEnabled = false
            e.presentation.text = "Renaming…"
            return
        }
        e.presentation.icon = AllIcons.Actions.InlayRenameInNoCodeFiles
        e.applyStrings(ActionStrings.RENAME_SESSION, selectedSession() != null)
    }

    override fun actionPerformed(e: AnActionEvent) = perform()

    fun perform() {
        if (isPending.get()) return
        val (sessionId, currentTitle) = selectedSession() ?: return

        // Show the input dialog first — it's modal so no need to be in pending state yet.
        val newTitle = Messages.showInputDialog(
            project,
            "Enter new session name:",
            "Rename Session",
            null,
            currentTitle,
            null,
        )
        if (newTitle.isNullOrBlank() || newTitle == currentTitle) return

        // User confirmed — now mark pending for the async HTTP call.
        if (!isPending.compareAndSet(false, true)) return
        OpenCodeTuiClient.getInstance(project).renameSession(sessionId, newTitle) { success, error ->
            isPending.set(false)
            if (!success) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showMessageDialog(
                        project,
                        "Failed to rename session: $error",
                        "Error",
                        Messages.getErrorIcon(),
                    )
                }
            }
        }
    }
}
