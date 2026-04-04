package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.FileDiff
import com.ashotn.opencode.relay.ipc.OpenCodeEvent
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import com.ashotn.opencode.relay.util.TextUtil
import com.ashotn.opencode.relay.util.toAbsolutePath
import com.intellij.openapi.diagnostic.Logger

internal class SessionDiffApplyComputer(
    private val contentReader: (String) -> String,
    private val hunkComputer: (FileDiff, String) -> List<DiffHunk>,
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
    ): StateStore.SessionDiffComputedState {
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
            val absPath = toAbsolutePath(projectBase, diffFile.file)

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

            val actualAfter = contentReader(absPath)
            val effectiveBefore = if (fromHistory) {
                diffFile.before
            } else {
                previousAfterByFile[absPath] ?: diffFile.before
            }

            val hasContentChange =
                TextUtil.normalizeContent(effectiveBefore) != TextUtil.normalizeContent(actualAfter)

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
                // On a historical load the server is the authority on whether a file
                // was touched in this session. Even if disk currently matches the
                // baseline (e.g. the user reverted the file), we must still record it
                // so it appears in the file list and the 3-panel diff viewer remains
                // accessible. For live turns we keep the existing behaviour: a file
                // that matches baseline is considered resolved and not shown.
                if (fromHistory && diffFile.status == SessionDiffStatus.MODIFIED) {
                    newHunksByFile[absPath] = emptyList()
                    newBaselineByFile[absPath] = effectiveBefore
                }
                continue
            }

            val fileDiff = FileDiff(
                file = absPath,
                before = effectiveBefore,
                after = actualAfter,
                additions = diffFile.additions,
                deletions = diffFile.deletions,
            )
            val hunks = hunkComputer(fileDiff, event.sessionId)
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
                // Still record the file with an empty hunk list so it remains visible
                // in the session file count and file list. This covers two cases:
                //   1. Hunk computation failed internally (e.g. ComparisonManager threw).
                //   2. The diff algorithm produced no hunks despite a content difference
                //      (shouldn't happen, but defensive).
                // Without this, the file silently disappears from tracking even though
                // the server reported it as modified.
                newHunksByFile[absPath] = emptyList()
                newBaselineByFile[absPath] = effectiveBefore
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
                    nextAfterByFile[scopedPath] = contentReader(scopedPath)
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
                    "analyses" to (analyses ?: emptyList()),
                ),
            )
        }

        return StateStore.SessionDiffComputedState(
            nextAfterByFile = nextAfterByFile,
            processedPaths = processedPaths,
            newHunksByFile = newHunksByFile,
            newDeleted = newDeleted,
            newAdded = newAdded,
            newBaselineByFile = newBaselineByFile,
        )
    }
}
