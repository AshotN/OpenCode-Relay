package com.ashotn.opencode.diff

import com.ashotn.opencode.diff.DiffTracer
import com.ashotn.opencode.ipc.FileDiff
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import com.intellij.openapi.diagnostic.Logger

internal class SessionDiffApplyComputer(
    private val contentReader: ContentReader,
    private val hunkComputer: HunkComputer,
    private val onFileProcessing: ((absPath: String, status: SessionDiffStatus) -> Unit)? = null,
    private val log: Logger,
    private val tracer: DiffTracer,
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
        val analyses = if (tracer.enabled) mutableListOf<Map<String, Any?>>() else null

        for (diffFile in event.files) {
            val absPath = DiffTextUtil.toAbsolutePath(projectBase, diffFile.file)

            if (!fromHistory && turnScope != null && absPath !in turnScope) {
                outOfScopeCount += 1
                analyses?.add(
                    mapOf(
                        "absPath" to absPath,
                        "status" to diffFile.status.name,
                        "inScope" to false,
                    ),
                )
                continue
            }

            processedPaths.add(absPath)

            if (!fromHistory) {
                onFileProcessing?.invoke(absPath, diffFile.status)
            }

            val actualAfter = contentReader.readCurrentContent(absPath)
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
                analyses?.add(
                    mapOf(
                        "absPath" to absPath,
                        "status" to diffFile.status.name,
                        "inScope" to true,
                        "previousAfterSize" to previousAfterByFile[absPath]?.length,
                        "effectiveBeforeSize" to effectiveBefore.length,
                        "actualAfterSize" to actualAfter.length,
                        "contentChanged" to false,
                    ),
                )
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
                analyses?.add(
                    mapOf(
                        "absPath" to absPath,
                        "status" to diffFile.status.name,
                        "inScope" to true,
                        "previousAfterSize" to previousAfterByFile[absPath]?.length,
                        "effectiveBeforeSize" to effectiveBefore.length,
                        "actualAfterSize" to actualAfter.length,
                        "contentChanged" to true,
                        "hunkCount" to 0,
                    ),
                )
                continue
            }

            newHunksByFile[absPath] = hunks
            newBaselineByFile[absPath] = effectiveBefore
            analyses?.add(
                mapOf(
                    "absPath" to absPath,
                    "status" to diffFile.status.name,
                    "inScope" to true,
                    "previousAfterSize" to previousAfterByFile[absPath]?.length,
                    "effectiveBeforeSize" to effectiveBefore.length,
                    "actualAfterSize" to actualAfter.length,
                    "contentChanged" to true,
                    "hunkCount" to hunks.size,
                ),
            )
        }

        // Any file that was in turnScope but absent from the server diff has been resolved back to
        // baseline. Mark it as processed (with no hunks) so that mergeMapByProcessedPaths evicts
        // the stale state rather than preserving it.
        if (!fromHistory && turnScope != null) {
            for (scopedPath in turnScope) {
                if (scopedPath !in processedPaths) {
                    processedPaths.add(scopedPath)
                    nextAfterByFile[scopedPath] = contentReader.readCurrentContent(scopedPath)
                    analyses?.add(
                        mapOf(
                            "absPath" to scopedPath,
                            "status" to "CLEARED",
                            "inScope" to true,
                            "absentFromServerDiff" to true,
                        ),
                    )
                }
            }
        }

        log.debug(
            "SessionDiffApplyComputer: compute session.diff session=${event.sessionId} fileCount=${event.files.size} emittedFileCount=${newHunksByFile.size} baselineMatchedFileCount=$baselineMatchCount zeroHunkFileCount=$zeroHunkCount outOfScopeFileCount=$outOfScopeCount fromHistory=$fromHistory",
        )
        if (tracer.enabled && (!fromHistory || tracer.includeHistory)) {
            tracer.record(
                kind = "session.diff.compute",
                fields = mapOf(
                    "sessionId" to event.sessionId,
                    "fromHistory" to fromHistory,
                    "turnScope" to turnScope?.toList(),
                    "inputFileCount" to event.files.size,
                    "emittedFileCount" to newHunksByFile.size,
                    "baselineMatchCount" to baselineMatchCount,
                    "zeroHunkCount" to zeroHunkCount,
                    "outOfScopeCount" to outOfScopeCount,
                    "analyses" to (analyses ?: emptyList<Map<String, Any?>>()),
                ),
            )
        }

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
