package com.ashotn.opencode.actions

import com.ashotn.opencode.OpenCodePlugin
import com.ashotn.opencode.diff.OpenCodeDiffService
import com.ashotn.opencode.util.applyStrings
import com.ashotn.opencode.util.showNotification
import com.ashotn.opencode.util.toProjectRelativePath
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Project View action that appends @path references for all selected directories to the
 * TUI's prompt input buffer via POST /tui/append-prompt.
 *
 * Only visible when at least one directory is selected. Files in the same selection are
 * ignored here — they are handled by SendFileAction.
 *
 * Sends references only — no file content is embedded in the prompt.
 */
class SendFolderAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val running = project != null && OpenCodePlugin.getInstance(project).isRunning
        val folders = resolveFolders(e)
        // Only show this action when there is at least one directory selected and we are
        // NOT in an editor (editor sends files, not folders)
        val inEditor = e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.isVisible = !inEditor && folders.isNotEmpty()
        e.presentation.icon = AllIcons.Nodes.Folder
        e.applyStrings(ActionStrings.SEND_FOLDER, running && folders.isNotEmpty())
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val folders = resolveFolders(e)
        if (folders.isEmpty()) return

        val projectBase = project.basePath
        val ref = folders.joinToString(" ") { folder ->
            val relativePath = projectBase?.let { folder.path.toProjectRelativePath(it) } ?: folder.path
            "@$relativePath"
        } + " "

        OpenCodeDiffService.getInstance(project).appendToTuiPrompt(ref) { success, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    val label = if (folders.size == 1) "Folder reference" else "${folders.size} folder references"
                    project.showNotification(
                        "Folder sent to OpenCode",
                        "$label appended to the active session's prompt.",
                        NotificationType.INFORMATION,
                    )
                } else {
                    project.showNotification(
                        "Failed to send folder",
                        error ?: "Could not reach the OpenCode server.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    private fun resolveFolders(e: AnActionEvent): List<VirtualFile> {
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return emptyList()
        return virtualFiles.filter { it.isDirectory }
    }
}
