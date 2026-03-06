package com.ashotn.opencode.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

private const val ADDED_HIGHLIGHT_LAYER = HighlighterLayer.SYNTAX + 500

/**
 * Subscribes to [DiffHunksChangedListener.TOPIC] and [FileEditorManagerListener]
 * to keep editor markup in sync with the current set of [DiffHunk]s.
 */
@Service(Service.Level.PROJECT)
class EditorDiffRenderer(private val project: Project) : Disposable, FileEditorManagerListener {

    private val log = logger<EditorDiffRenderer>()

    private data class ManagedHighlighter(
        val model: MarkupModel,
        val highlighter: RangeHighlighter,
    )

    private data class FileMarkup(
        val highlighters: MutableList<ManagedHighlighter> = mutableListOf(),
        val inlays: MutableList<Inlay<*>> = mutableListOf(),
    )

    private val markupByFile = mutableMapOf<String, FileMarkup>()

    init {
        val bus = project.messageBus.connect(this)
        bus.subscribe(DiffHunksChangedListener.TOPIC, DiffHunksChangedListener { filePath ->
            ApplicationManager.getApplication().invokeLater {
                refreshFile(filePath)
            }
        })
        bus.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val filePath = file.path
        val diffService = OpenCodeDiffService.getInstance(project)
        if (diffService.hasPendingHunks(filePath)) {
            log.debug("EditorDiffRenderer: fileOpened with pending hunks: $filePath")
            ApplicationManager.getApplication().invokeLater {
                refreshFile(filePath)
            }
        }
    }

    private fun refreshFile(filePath: String) {
        clearMarkup(filePath)

        val diffService = OpenCodeDiffService.getInstance(project)
        val hunks = diffService.getHunks(filePath).filter { it.state == HunkState.PENDING }
        log.debug("EditorDiffRenderer: refreshFile $filePath - ${hunks.size} pending hunks")
        if (hunks.isEmpty()) return

        val editors = editorsForPath(filePath)
        log.debug("EditorDiffRenderer: found ${editors.size} open editors for $filePath")
        if (editors.isEmpty()) return

        val document = editors.first().document
        val markup = FileMarkup()

        log.debug("EditorDiffRenderer: document lineCount=${document.lineCount}")

        for (hunk in hunks) {
            log.debug(
                "EditorDiffRenderer: rendering hunk startLine=${hunk.startLine} removed=${hunk.removedLines.size} added=${hunk.addedLines.size}",
            )

            if (hunk.addedLines.isNotEmpty() && document.lineCount > 0) {
                val startLine = hunk.startLine.coerceIn(0, document.lineCount - 1)
                val endLine = (hunk.startLine + hunk.addedLines.size - 1).coerceIn(0, document.lineCount - 1)
                val startOffset = document.getLineStartOffset(startLine)
                val endOffset = document.getLineEndOffset(endLine)

                val addedAttrs = TextAttributes().apply {
                    backgroundColor = DiffHighlightStyles.style(DiffHighlightKind.ADDED).bg
                }

                editors.forEach { editor ->
                    val model = editor.markupModel
                    val highlighter = model.addRangeHighlighter(
                        startOffset,
                        endOffset,
                        ADDED_HIGHLIGHT_LAYER,
                        addedAttrs,
                        HighlighterTargetArea.LINES_IN_RANGE,
                    )
                    markup.highlighters.add(ManagedHighlighter(model, highlighter))
                }
            }

            if (hunk.removedLines.isNotEmpty() && document.lineCount > 0) {
                val inlayLine = hunk.startLine.coerceIn(0, document.lineCount - 1)
                val inlayOffset = document.getLineStartOffset(inlayLine)
                editors.forEach { editor ->
                    val inlay = editor.inlayModel.addBlockElement(
                        inlayOffset,
                        false,
                        true,
                        0,
                        RemovedLinesRenderer(hunk.removedLines, editor),
                    )
                    if (inlay != null) markup.inlays.add(inlay)
                }
            }
        }

        markupByFile[filePath] = markup
        log.debug("EditorDiffRenderer: done - ${markup.highlighters.size} highlighters, ${markup.inlays.size} inlays")
    }

    private fun clearMarkup(filePath: String) {
        val markup = markupByFile.remove(filePath) ?: return
        markup.highlighters.forEach { managed ->
            if (managed.highlighter.isValid) {
                managed.model.removeHighlighter(managed.highlighter)
            }
        }
        markup.inlays.forEach { it.dispose() }
    }

    fun clearAll() {
        val paths = markupByFile.keys.toList()
        paths.forEach { clearMarkup(it) }
    }

    private fun editorsForPath(filePath: String): List<Editor> {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return emptyList()
        return FileEditorManager.getInstance(project)
            .getAllEditors(virtualFile)
            .filterIsInstance<TextEditor>()
            .map { it.editor }
    }

    override fun dispose() {
        clearAll()
    }

    companion object {
        fun getInstance(project: Project): EditorDiffRenderer =
            project.getService(EditorDiffRenderer::class.java)
    }
}
