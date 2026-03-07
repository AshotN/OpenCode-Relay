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
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project

/**
 * Editor action that appends the current text selection as a fenced code block
 * to the TUI's prompt input buffer via POST /tui/append-prompt.
 *
 * The OpenCode server provides no session-scoping for this endpoint — the text lands
 * in whichever session the TUI currently has open. The plugin has no control over that.
 *
 * Does NOT create a message or trigger a model response; the user still submits manually.
 */
class SendSelectionAction : AnAction(
    ActionStrings.SEND_SELECTION.text,
    ActionStrings.SEND_SELECTION.description,
    AllIcons.Actions.Upload,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val running = project != null && OpenCodePlugin.getInstance(project).isRunning
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        val strings = ActionStrings.SEND_SELECTION

        // Hide entirely when invoked outside an editor context (e.g. tool window, global shortcut)
        e.presentation.isVisible = editor != null
        e.applyStrings(strings, running && hasSelection)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            showNotification(project, "No text selected", "Select some code in the editor first.", NotificationType.INFORMATION)
            return
        }

        val selectedText = selectionModel.selectedText ?: return
        if (selectedText.isBlank()) {
            showNotification(project, "Empty selection", "The selected text is blank.", NotificationType.INFORMATION)
            return
        }

        val contextText = buildContextPayload(project, editor, selectedText)

        val diffService = OpenCodeDiffService.getInstance(project)
        diffService.appendToTuiPrompt(contextText) { success, error ->
            ApplicationManager.getApplication().invokeLater {
                if (success) {
                    showNotification(
                        project,
                        "Selection sent to OpenCode",
                        "The selected code has been appended to the active session's prompt.",
                        NotificationType.INFORMATION,
                    )
                } else {
                    showNotification(
                        project,
                        "Failed to send selection",
                        error ?: "Could not reach the OpenCode server.",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    private fun buildContextPayload(project: Project, editor: Editor, selectedText: String): String {
        val document = editor.document
        val selectionModel = editor.selectionModel

        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        val startLine = document.getLineNumber(startOffset) + 1  // 1-based
        val endLine = document.getLineNumber(endOffset - 1).coerceAtLeast(startLine) + 1

        val virtualFile = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getFile(document)
        val filePath = when {
            virtualFile != null -> {
                val projectBase = project.basePath
                if (projectBase != null) virtualFile.path.toProjectRelativePath(projectBase)
                else virtualFile.path
            }
            else -> "untitled"
        }

        val language = virtualFile?.extension ?: ""

        return buildString {
            append("```$language\n")
            append("// $filePath lines $startLine-$endLine\n")
            append(selectedText)
            if (!selectedText.endsWith('\n')) append('\n')
            append("```")
        }
    }

    private fun showNotification(project: Project, title: String, content: String, type: NotificationType) {
        project.showNotification(title, content, type)
    }
}
