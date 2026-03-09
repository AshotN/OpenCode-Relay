package com.ashotn.opencode.companion.diff

import com.ashotn.opencode.companion.ipc.SessionDiffStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.ConcurrentHashMap

internal class DocumentSyncService(
    private val project: Project,
) {
    private val lastDocumentReloadAtByPath = ConcurrentHashMap<String, Long>()
    private val refreshCoordinator = VfsRefreshCoordinator()

    fun queueVfsRefresh(absPath: String, status: SessionDiffStatus) {
        when (status) {
            SessionDiffStatus.MODIFIED -> {
                refreshCoordinator.enqueue(absPath, recursive = false)
            }

            SessionDiffStatus.ADDED -> {
                refreshCoordinator.enqueue(absPath, recursive = false)
                parentPath(absPath)?.let { refreshCoordinator.enqueue(it, recursive = true) }
            }

            SessionDiffStatus.DELETED -> {
                parentPath(absPath)?.let { refreshCoordinator.enqueue(it, recursive = true) }
            }

            SessionDiffStatus.UNKNOWN -> {
                refreshCoordinator.enqueue(absPath, recursive = false)
                parentPath(absPath)?.let { refreshCoordinator.enqueue(it, recursive = true) }
            }
        }
    }

    fun flushQueuedVfsRefreshes() {
        refreshCoordinator.flushNow(trigger = "explicit")
    }

    fun reloadOpenDocument(absPath: String) {
        if (isMarkdownFile(absPath)) return
        if (project.isDisposed) return

        val vFile = LocalFileSystem.getInstance().findFileByPath(absPath) ?: return
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getCachedDocument(vFile) ?: return
        if (!shouldReloadDocument(absPath)) return

        val reloadAction = {
            if (!project.isDisposed && !fileDocumentManager.isDocumentUnsaved(document)) {
                fileDocumentManager.reloadFromDisk(document)
            }
        }

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            reloadAction()
        } else {
            app.invokeLater {
                if (!project.isDisposed) {
                    reloadAction()
                }
            }
        }
    }

    fun readCurrentContent(absPath: String): String {
        val file = java.io.File(absPath)
        if (!file.exists()) return ""
        return try {
            file.readText()
        } catch (_: Exception) {
            ""
        }
    }

    fun reset() {
        lastDocumentReloadAtByPath.clear()
        refreshCoordinator.clearPending()
    }

    fun dispose() {
        lastDocumentReloadAtByPath.clear()
        refreshCoordinator.dispose()
    }

    private fun shouldReloadDocument(absPath: String): Boolean {
        val now = System.currentTimeMillis()
        var shouldReload = false

        lastDocumentReloadAtByPath.compute(absPath) { _, previousReloadAt ->
            val lastReloadAt = previousReloadAt ?: 0L
            if (now - lastReloadAt >= DOCUMENT_RELOAD_COOLDOWN_MILLIS) {
                shouldReload = true
                now
            } else {
                lastReloadAt
            }
        }

        return shouldReload
    }

    private fun isMarkdownFile(absPath: String): Boolean {
        val lowerPath = absPath.lowercase()
        return lowerPath.endsWith(".md") || lowerPath.endsWith(".markdown") || lowerPath.endsWith(".mdx")
    }

    private fun parentPath(absPath: String): String? = java.io.File(absPath).parent

    companion object {
        private const val DOCUMENT_RELOAD_COOLDOWN_MILLIS = 500L
    }
}
