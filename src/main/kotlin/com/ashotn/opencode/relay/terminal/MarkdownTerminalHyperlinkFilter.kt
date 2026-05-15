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

// Adds clickable local file links in embedded JediTerm output. Supported examples:
// - Markdown links: [File.kt](./src/File.kt), [File.kt:42](src/main/File.kt#L42)
// - Bare paths: ./src/File.kt, ../src/File.kt, /abs/path/File.kt, src/main/File.kt
// - Line anchors: ./src/File.kt#L42, src/main/File.kt#L42-L48, note.md#L2
// - Line suffixes: ./src/File.kt:42, src/main/File.kt:42-48, /abs/path/File.kt:42
// Candidates only become links after resolving to real local files; URI-like targets are ignored.
private val markdownLinkRegex = Regex("""\[([^\]\r\n]+)]\(([^)\s]+)\)""")
private val lineAnchorReferenceRegex = Regex("""(?<![\w./-])((?:\.{1,2}/|/)?[^\s()<>"]+?#L\d+(?:-L\d+)?)(?![\w./-])""")
private val lineSuffixReferenceRegex =
    Regex("""(?<![\w./-])((?:\.{1,2}/|/|[\w.-]+/)[^\s\[\]()<>"]+?:\d+(?:-\d+)?)(?![\w./-])""")
private val localFileReferenceRegex = Regex("""(?<![\w./-])((?:\.{1,2}/|/|[\w.-]+/)[^\s\[\]()<>"]+)(?![\w./-])""")
private val lineAnchorRegex = Regex("""^(.*)#L(\d+)(?:-L\d+)?$""")
private val lineSuffixRegex = Regex("""^(.*):(\d+)(?:-\d+)?$""")
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
        val hasLocalFileCandidate = '/' in line
        val hasLineSuffixCandidate = ':' in line
        if (!hasMarkdownCandidate && !hasLineAnchorCandidate && !hasLocalFileCandidate && !hasLineSuffixCandidate) return null

        val items = mutableListOf<LinkResultItem>()
        val consumedRanges = mutableListOf<IntRange>()

        fun createLinkForMatch(matchRange: IntRange, target: String): LinkResultItem? {
            if (consumedRanges.any { range ->
                    rangesOverlap(
                        matchRange.first,
                        matchRange.last + 1,
                        range.first,
                        range.last + 1
                    )
                }) {
                return null
            }

            val resolvedTarget = resolveTerminalLinkTarget(target) ?: return null
            consumedRanges.add(matchRange)
            return createLinkResultItem(matchRange.first, matchRange.last + 1, resolvedTarget)
        }

        if (hasMarkdownCandidate) {
            markdownLinkRegex.findAll(line).mapNotNullTo(items) { match ->
                createLinkForMatch(match.range, match.groupValues[2])
            }
        }

        if (hasLineAnchorCandidate) {
            lineAnchorReferenceRegex.findAll(line).mapNotNullTo(items) { match ->
                createLinkForMatch(match.range, match.groupValues[1])
            }
        }

        if (hasLineSuffixCandidate) {
            lineSuffixReferenceRegex.findAll(line).mapNotNullTo(items) { match ->
                createLinkForMatch(match.range, match.groupValues[1])
            }
        }

        if (hasLocalFileCandidate) {
            localFileReferenceRegex.findAll(line).mapNotNullTo(items) { match ->
                createLinkForMatch(match.range, match.groupValues[1])
            }
        }

        return items.takeIf { it.isNotEmpty() }?.let(::LinkResult)
    }

    private fun createLinkResultItem(startOffset: Int, endOffset: Int, target: ResolvedTerminalLink): LinkResultItem =
        LinkResultItem(startOffset, endOffset, LinkInfo { navigate(target.virtualFile, target.lineNumber) })

    private fun resolveTerminalLinkTarget(target: String): ResolvedTerminalLink? {
        val parsedTarget = parseLineAnchor(target)
        // Exclude URIs
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
            val suffix = lineSuffixRegex.matchEntire(target)
            if (suffix != null) {
                ParsedTerminalLinkTarget(suffix.groupValues[1], suffix.groupValues[2].toIntOrNull()?.coerceAtLeast(1))
            } else {
                ParsedTerminalLinkTarget(target, null)
            }
        }
    }

    private fun rangesOverlap(start: Int, end: Int, otherStart: Int, otherEnd: Int): Boolean =
        start < otherEnd && otherStart < end

    private fun decodePath(path: String): String =
        runCatching { URI(null, null, path, null).path ?: path }.getOrDefault(path)
}

private data class ParsedTerminalLinkTarget(
    val path: String,
    val lineNumber: Int?,
)

private data class ResolvedTerminalLink(
    val virtualFile: VirtualFile,
    val lineNumber: Int?,
)
