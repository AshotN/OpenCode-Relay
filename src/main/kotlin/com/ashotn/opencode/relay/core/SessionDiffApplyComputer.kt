package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.FileDiff
import com.ashotn.opencode.relay.api.session.SessionDiffSnapshot
import com.ashotn.opencode.relay.ipc.SessionDiffStatus
import com.ashotn.opencode.relay.util.TextUtil
import com.ashotn.opencode.relay.util.createPathIdentityMap
import com.ashotn.opencode.relay.util.createPathIdentitySet
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
        event: SessionDiffSnapshot,
        fromHistory: Boolean,
    ): StateStore.SessionDiffComputedState {
        val newHunksByFile = createPathIdentityMap<List<DiffHunk>>(projectBase)
        val newDeleted = createPathIdentitySet(projectBase)
        val newAdded = createPathIdentitySet(projectBase)
        val newBaselineByFile = createPathIdentityMap<String>(projectBase)
        val processedPaths = createPathIdentitySet(projectBase)
        var baselineMatchCount = 0
        var zeroHunkCount = 0
        val analyses = if (tracer.enabled) mutableListOf<Map<String, Any?>>() else null

        for (diffFile in event.files) {
            val absPath = toAbsolutePath(projectBase, diffFile.file)
            processedPaths.add(absPath)

            if (!fromHistory) {
                onFileProcessing?.invoke(absPath, diffFile.status)
            }

            val actualAfter = contentReader(absPath)
            val effectiveBefore = diffFile.before

            val hasContentChange =
                TextUtil.normalizeContent(effectiveBefore) != TextUtil.normalizeContent(actualAfter)

            if (!hasContentChange) {
                baselineMatchCount += 1
                analyses?.add(
                    mapOf(
                        "absPath" to absPath,
                        "status" to diffFile.status.name,
                        "effectiveBeforeSize" to effectiveBefore.length,
                        "actualAfterSize" to actualAfter.length,
                        "contentChanged" to false,
                    ),
                )
                continue
            }

            if (diffFile.status == SessionDiffStatus.DELETED) newDeleted.add(absPath)
            if (diffFile.status == SessionDiffStatus.ADDED) newAdded.add(absPath)

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
                    "effectiveBeforeSize" to effectiveBefore.length,
                    "actualAfterSize" to actualAfter.length,
                    "contentChanged" to true,
                    "hunkCount" to hunks.size,
                ),
            )
        }

        log.debug(
            "SessionDiffApplyComputer: compute diff.apply session=${event.sessionId} fileCount=${event.files.size} emittedFileCount=${newHunksByFile.size} baselineMatchedFileCount=$baselineMatchCount zeroHunkFileCount=$zeroHunkCount fromHistory=$fromHistory",
        )
        if (tracer.enabled && (!fromHistory || tracer.includeHistory)) {
            tracer.record(
                kind = "diff.apply.compute",
                fields = mapOf(
                    "sessionId" to event.sessionId,
                    "fromHistory" to fromHistory,
                    "inputFileCount" to event.files.size,
                    "emittedFileCount" to newHunksByFile.size,
                    "baselineMatchCount" to baselineMatchCount,
                    "zeroHunkCount" to zeroHunkCount,
                    "analyses" to (analyses ?: emptyList()),
                ),
            )
        }

        return StateStore.SessionDiffComputedState(
            projectBase = projectBase,
            processedPaths = processedPaths,
            newHunksByFile = newHunksByFile,
            newDeleted = newDeleted,
            newAdded = newAdded,
            newBaselineByFile = newBaselineByFile,
        )
    }
}
