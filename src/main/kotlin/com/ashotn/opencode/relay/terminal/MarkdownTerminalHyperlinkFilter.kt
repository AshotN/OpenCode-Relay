package com.ashotn.opencode.relay.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.terminal.JBTerminalPanel
import com.jediterm.terminal.model.hyperlinks.HyperlinkFilter
import com.jediterm.terminal.model.hyperlinks.LinkInfo
import com.jediterm.terminal.model.hyperlinks.LinkResult
import com.jediterm.terminal.model.hyperlinks.LinkResultItem
import com.jediterm.terminal.model.hyperlinks.TextProcessing
import java.io.File
import java.net.URI

// Adds clickable local file links for Markdown links and bare #L references in embedded JediTerm output.
private val markdownLinkRegex = Regex("""\[([^\]\r\n]+)]\(([^)\s]+)\)""")
private val lineAnchorReferenceRegex = Regex("""(?<![\w./-])((?:\.{1,2}/|/)?[^\s()<>"]+?#L\d+(?:-L\d+)?)(?![\w./-])""")
private val lineAnchorRegex = Regex("""^(.*)#L(\d+)(?:-L\d+)?$""")
private val logger = Logger.getInstance(MarkdownTerminalHyperlinkFilter::class.java)

internal fun installMarkdownTerminalHyperlinkFilter(project: Project, panel: JBTerminalPanel) {
    runCatching {
        val textProcessing = panel.terminalTextProcessing()
        if (textProcessing == null) {
            logger.warn("Skipping Markdown terminal hyperlink filter because JediTerm text processing is unavailable")
            return@runCatching
        }
        textProcessing.addHyperlinkFilter(MarkdownTerminalHyperlinkFilter(project))
    }.onFailure { error ->
        logger.warn("Failed to install Markdown terminal hyperlink filter", error)
    }
}

private fun JBTerminalPanel.terminalTextProcessing(): TextProcessing? {
    val method = terminalTextBuffer.javaClass.methods.firstOrNull { method ->
        method.parameterCount == 0 && TextProcessing::class.java.isAssignableFrom(method.returnType)
    } ?: return null
    method.isAccessible = true
    return method.invoke(terminalTextBuffer) as? TextProcessing
}

internal class MarkdownTerminalHyperlinkFilter(
    private val project: Project,
    private val navigate: (VirtualFile, Int?) -> Unit = { virtualFile, lineNumber ->
        ApplicationManager.getApplication().invokeLater {
            if (lineNumber != null) {
                OpenFileDescriptor(project, virtualFile, lineNumber - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, virtualFile).navigate(true)
            }
        }
    },
) : HyperlinkFilter {
    override fun apply(line: String): LinkResult? {
        val hasMarkdownCandidate = '[' in line
        val hasLineAnchorCandidate = "#L" in line
        if (!hasMarkdownCandidate && !hasLineAnchorCandidate) return null

        val items = mutableListOf<LinkResultItem>()
        val consumedRanges = mutableListOf<IntRange>()

        if (hasMarkdownCandidate) {
            markdownLinkRegex.findAll(line).mapNotNullTo(items) { match ->
                val target = match.groupValues[2]
                val resolvedTarget = resolveTerminalLinkTarget(target) ?: return@mapNotNullTo null
                consumedRanges.add(match.range)
                val labelStart = match.range.first + 1
                val labelEnd = labelStart + match.groupValues[1].length
                createLinkResultItem(labelStart, labelEnd, resolvedTarget)
            }
        }

        if (hasLineAnchorCandidate) {
            lineAnchorReferenceRegex.findAll(line).mapNotNullTo(items) { match ->
                if (consumedRanges.any { range -> rangesOverlap(match.range.first, match.range.last + 1, range.first, range.last + 1) }) {
                    return@mapNotNullTo null
                }
                val target = match.groupValues[1]
                val resolvedTarget = resolveTerminalLinkTarget(target) ?: return@mapNotNullTo null
                createLinkResultItem(match.range.first, match.range.last + 1, resolvedTarget)
            }
        }

        return items.takeIf { it.isNotEmpty() }?.let(::LinkResult)
    }

    private fun createLinkResultItem(startOffset: Int, endOffset: Int, target: ResolvedTerminalLink): LinkResultItem =
        LinkResultItem(startOffset, endOffset, LinkInfo { navigate(target.virtualFile, target.lineNumber) })

    private fun resolveTerminalLinkTarget(target: String): ResolvedTerminalLink? {
        val parsedTarget = parseLineAnchor(target)
        if (parsedTarget.path.contains("://")) return null

        val basePath = project.basePath ?: return null
        val file = File(parsedTarget.path).let { path ->
            if (path.isAbsolute) path else File(basePath, decodePath(parsedTarget.path))
        }

        if (!file.isFile) return null
        return LocalFileSystem.getInstance().findFileByIoFile(file)
            ?.let { ResolvedTerminalLink(it, parsedTarget.lineNumber) }
    }

    private fun parseLineAnchor(target: String): ParsedTerminalLinkTarget {
        val anchor = lineAnchorRegex.matchEntire(target)
        return if (anchor != null) {
            ParsedTerminalLinkTarget(anchor.groupValues[1], anchor.groupValues[2].toIntOrNull()?.coerceAtLeast(1))
        } else {
            ParsedTerminalLinkTarget(target, null)
        }
    }

    private fun rangesOverlap(start: Int, end: Int, otherStart: Int, otherEnd: Int): Boolean =
        start < otherEnd && otherStart < end

    private fun decodePath(path: String): String =
        runCatching { URI(null, null, path, null).path }.getOrDefault(path)
}

private data class ParsedTerminalLinkTarget(
    val path: String,
    val lineNumber: Int?,
)

private data class ResolvedTerminalLink(
    val virtualFile: VirtualFile,
    val lineNumber: Int?,
)
