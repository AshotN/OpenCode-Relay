package com.ashotn.opencode.relay.actions

import com.ashotn.opencode.relay.OpenCodePlugin
import com.ashotn.opencode.relay.tui.OpenCodeTuiClient
import com.ashotn.opencode.relay.util.applyStrings
import com.ashotn.opencode.relay.util.showNotification
import com.ashotn.opencode.relay.util.toProjectRelativePath
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Action that appends an @path file reference to the TUI's prompt input buffer via
 * POST /tui/append-prompt.
 *
 * Works in two contexts:
 *  - Editor: sends a reference to the file currently open in the editor.
 *  - Project View: sends references to all selected files (directories excluded — use SendFolderAction for those).
 *
 * Sends references only — no file content is embedded in the prompt.
 */
class SendFileAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val running = project != null && OpenCodePlugin.getInstance(project).isRunning
        val hasFile = resolveFiles(e).isNotEmpty()
        e.presentation.isVisible = hasFile
        e.presentation.icon = AllIcons.FileTypes.Any_type
        e.applyStrings(ActionStrings.SEND_FILE, running && hasFile)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = resolveFiles(e)
        if (files.isEmpty()) return

        val projectBase = project.basePath
        val ref = files.joinToString(" ") { file ->
            val relativePath = projectBase?.let { file.path.toProjectRelativePath(it) } ?: file.path
            "@$relativePath"
        } + " "

        OpenCodeTuiClient.getInstance(project).appendToTuiPrompt(ref) { success, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    val label = if (files.size == 1) "File reference" else "${files.size} file references"
                    project.showNotification(
                        "File sent to OpenCode",
                        "$label appended to the active session's prompt.",
                        NotificationType.INFORMATION,
                    )
                } else {
                    project.showNotification(
                        "Failed to send file",
                        error ?: "Could not reach the OpenCode server.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    /**
     * Returns the files to reference:
     *  - In an editor context: the single file open in the editor.
     *  - In a Project View context: all selected files (non-directory).
     */
    private fun resolveFiles(e: AnActionEvent): List<VirtualFile> {
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            // Editor context — use the file backing the editor
            val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
            return if (file != null && !file.isDirectory) listOf(file) else emptyList()
        }

        // Project View context — filter out directories (those go to SendFolderAction)
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return emptyList()
        return virtualFiles.filter { !it.isDirectory }
    }
}
