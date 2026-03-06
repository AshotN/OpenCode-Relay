package com.ashotn.opencode.diff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.util.concurrent.ConcurrentHashMap

internal class DocumentSyncService {
    private val lastDocumentReloadAtByPath = ConcurrentHashMap<String, Long>()

    fun refreshVfs(absPath: String, wasDeleted: Boolean) {
        val lfs = LocalFileSystem.getInstance()
        val fileOnDisk = java.io.File(absPath)

        if (wasDeleted) {
            val parentPath = fileOnDisk.parent ?: return
            val parentVFile = lfs.refreshAndFindFileByPath(parentPath) ?: lfs.findFileByPath(parentPath) ?: return
            parentVFile.refresh(false, true)
            return
        }

        val vFile = lfs.refreshAndFindFileByPath(absPath) ?: lfs.findFileByPath(absPath)
        if (vFile != null) {
            vFile.refresh(false, false)
            return
        }

        var ancestor = fileOnDisk.parentFile
        var ancestorVFile = null as com.intellij.openapi.vfs.VirtualFile?
        while (ancestor != null) {
            ancestorVFile = lfs.refreshAndFindFileByPath(ancestor.path) ?: lfs.findFileByPath(ancestor.path)
            if (ancestorVFile != null) break
            ancestor = ancestor.parentFile
        }
        ancestorVFile?.refresh(false, true)
    }

    fun reloadOpenDocument(absPath: String) {
        if (!shouldReloadDocument(absPath)) return

        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
            ?: LocalFileSystem.getInstance().findFileByPath(absPath)
            ?: return

        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getCachedDocument(vFile) ?: return

        val reloadAction = {
            if (!fileDocumentManager.isDocumentUnsaved(document)) {
                fileDocumentManager.reloadFromDisk(document)
                lastDocumentReloadAtByPath[absPath] = System.currentTimeMillis()
            }
        }

        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            reloadAction()
        } else {
            app.invokeLater(reloadAction)
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

    fun clearReloadHistory() {
        lastDocumentReloadAtByPath.clear()
    }

    private fun shouldReloadDocument(absPath: String): Boolean {
        val lowerPath = absPath.lowercase()
        if (lowerPath.endsWith(".md") || lowerPath.endsWith(".markdown") || lowerPath.endsWith(".mdx")) {
            return false
        }

        val now = System.currentTimeMillis()
        val lastReloadAt = lastDocumentReloadAtByPath[absPath] ?: 0L
        return now - lastReloadAt >= DOCUMENT_RELOAD_COOLDOWN_MILLIS
    }

    companion object {
        private const val DOCUMENT_RELOAD_COOLDOWN_MILLIS = 500L
    }
}
