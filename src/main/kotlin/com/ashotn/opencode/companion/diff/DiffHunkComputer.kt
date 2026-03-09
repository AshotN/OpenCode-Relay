package com.ashotn.opencode.companion.diff

import com.ashotn.opencode.companion.ipc.FileDiff
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator

internal class DiffHunkComputer(private val logger: Logger) {
    fun compute(fileDiff: FileDiff, sessionId: String): List<DiffHunk> {
        // "".lines() returns [""] in Kotlin, which would make an empty file look like it has one
        // blank line. Use emptyList() for empty content so removed/added line ranges are correct.
        val beforeLines = if (fileDiff.before.isEmpty()) emptyList() else fileDiff.before.lines()
        val afterLines = if (fileDiff.after.isEmpty()) emptyList() else fileDiff.after.lines()
        return try {
            val changes = ComparisonManager.getInstance()
                .compareLines(fileDiff.before, fileDiff.after, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
            changes.map { change ->
                val beforeStart = change.startLine1.coerceAtMost(beforeLines.size)
                val beforeEnd = change.endLine1.coerceAtMost(beforeLines.size)
                val afterStart = change.startLine2.coerceAtMost(afterLines.size)
                val afterEnd = change.endLine2.coerceAtMost(afterLines.size)
                DiffHunk(
                    filePath = fileDiff.file,
                    startLine = change.startLine2,
                    removedLines = beforeLines.subList(beforeStart, beforeEnd),
                    addedLines = afterLines.subList(afterStart, afterEnd),
                    sessionId = sessionId,
                )
            }
        } catch (e: Exception) {
            logger.warn("OpenCodeDiffService: failed to compute hunks for ${fileDiff.file}", e)
            emptyList()
        }
    }
}
