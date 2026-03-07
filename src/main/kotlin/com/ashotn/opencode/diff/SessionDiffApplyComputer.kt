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
        val nextAfterByFile = previousAfterByFile.toMutableMap()

        for (diffFile in event.files) {
            val absPath = DiffTextUtil.toAbsolutePath(projectBase, diffFile.file)

            if (!fromHistory && turnScope != null && absPath !in turnScope) {
                continue
            }

            documentSyncService.refreshVfs(absPath, wasDeleted = diffFile.status == SessionDiffStatus.DELETED)
            documentSyncService.reloadOpenDocument(absPath)

            val actualAfter = documentSyncService.readCurrentContent(absPath)
            val effectiveBefore = if (fromHistory) {
                diffFile.before
            } else {
                previousAfterByFile[absPath] ?: diffFile.before
            }

            val hasContentChange =
                DiffTextUtil.normalizeContent(effectiveBefore) != DiffTextUtil.normalizeContent(actualAfter)

            log.debug(
                "OpenCodeDiffService: file=${diffFile.file} status=${diffFile.status} beforeEqDisk=${!hasContentChange} additions=${diffFile.additions} deletions=${diffFile.deletions}",
            )

            nextAfterByFile[absPath] = actualAfter

            if (!hasContentChange) {
                log.debug("OpenCodeDiffService: skip baseline-matching file=${diffFile.file}")
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
            log.debug("OpenCodeDiffService: file=${diffFile.file} hunks=${hunks.size}")
            if (hunks.isEmpty()) {
                log.debug("OpenCodeDiffService: skip zero-hunk file=${diffFile.file}")
                continue
            }

            newHunksByFile[absPath] = hunks
            newBaselineByFile[absPath] = effectiveBefore
            if (actualAfter.isEmpty()) newDeleted.add(absPath)
            if (diffFile.status == SessionDiffStatus.ADDED && actualAfter.isNotEmpty()) newAdded.add(absPath)
        }

        return DiffStateStore.SessionDiffComputedState(
            nextAfterByFile = nextAfterByFile,
            newHunksByFile = newHunksByFile,
            newDeleted = newDeleted,
            newAdded = newAdded,
            newBaselineByFile = newBaselineByFile,
        )
    }
}
