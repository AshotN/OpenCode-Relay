package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.FileDiff
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import com.intellij.openapi.diagnostic.Logger

internal class SessionDiffApplyComputer(
    private val documentSyncService: DocumentSyncService,
    private val hunkComputer: DiffHunkComputer,
    private val log: Logger,
) {
    fun compute(
        projectBase: String,
        event: OpenCodeEvent.SessionDiff,
        fromHistory: Boolean,
        turnScope: Set<String>?,
        previousAfterByFile: Map<String, String>,
    ): DiffStateStore.SessionDiffComputedState {
        val newHunksByFile = HashMap<String, List<DiffHunk>>()
        val newDeleted = HashSet<String>()
        val newAdded = HashSet<String>()
        val newBaselineByFile = HashMap<String, String>()
        val processedPaths = HashSet<String>()
        val nextAfterByFile = previousAfterByFile.toMutableMap()
        var outOfScopeCount = 0
        var baselineMatchCount = 0
        var zeroHunkCount = 0

        for (diffFile in event.files) {
            val absPath = DiffTextUtil.toAbsolutePath(projectBase, diffFile.file)

            if (!fromHistory && turnScope != null && absPath !in turnScope) {
                outOfScopeCount += 1
                continue
            }

            processedPaths.add(absPath)

            if (!fromHistory) {
                documentSyncService.queueVfsRefresh(absPath, diffFile.status)
                documentSyncService.reloadOpenDocument(absPath)
            }

            val actualAfter = documentSyncService.readCurrentContent(absPath)
            val effectiveBefore = if (fromHistory) {
                diffFile.before
            } else {
                previousAfterByFile[absPath] ?: diffFile.before
            }

            val hasContentChange =
                DiffTextUtil.normalizeContent(effectiveBefore) != DiffTextUtil.normalizeContent(actualAfter)

            nextAfterByFile[absPath] = actualAfter

            if (diffFile.status == SessionDiffStatus.DELETED) newDeleted.add(absPath)
            if (diffFile.status == SessionDiffStatus.ADDED) newAdded.add(absPath)

            if (!hasContentChange) {
                baselineMatchCount += 1
                continue
            }

            val fileDiff = FileDiff(
                file = absPath,
                before = effectiveBefore,
                after = actualAfter,
                additions = diffFile.additions,
                deletions = diffFile.deletions,
            )
            val hunks = hunkComputer.compute(fileDiff, event.sessionId)
            if (hunks.isEmpty()) {
                zeroHunkCount += 1
                continue
            }

            newHunksByFile[absPath] = hunks
            newBaselineByFile[absPath] = effectiveBefore
        }

        log.debug(
            "SessionDiffApplyComputer: compute session.diff session=${event.sessionId} fileCount=${event.files.size} emittedFileCount=${newHunksByFile.size} baselineMatchedFileCount=$baselineMatchCount zeroHunkFileCount=$zeroHunkCount outOfScopeFileCount=$outOfScopeCount fromHistory=$fromHistory",
        )

        return DiffStateStore.SessionDiffComputedState(
            nextAfterByFile = nextAfterByFile,
            processedPaths = processedPaths,
            newHunksByFile = newHunksByFile,
            newDeleted = newDeleted,
            newAdded = newAdded,
            newBaselineByFile = newBaselineByFile,
        )
    }
}
