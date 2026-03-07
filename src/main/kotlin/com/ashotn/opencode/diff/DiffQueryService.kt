package com.ashotn.opencode.diff

internal class DiffQueryService {
    fun visibleFiles(
        familySessionIds: () -> Set<String>,
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        addedBySession: Map<String, Set<String>>,
        deletedBySession: Map<String, Set<String>>,
    ): Set<String> = familySessionIds()
        .flatMap { sessionId ->
            (hunksBySessionAndFile[sessionId]?.keys ?: emptySet()) +
                    (addedBySession[sessionId] ?: emptySet()) +
                    (deletedBySession[sessionId] ?: emptySet())
        }
        .toSet()

    fun liveVisibleFiles(
        familySessionIds: () -> Set<String>,
        liveHunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        addedBySession: Map<String, Set<String>>,
        deletedBySession: Map<String, Set<String>>,
    ): Set<String> = familySessionIds()
        .flatMap { sessionId ->
            (liveHunksBySessionAndFile[sessionId]?.keys ?: emptySet()) +
                    (addedBySession[sessionId] ?: emptySet()) +
                    (deletedBySession[sessionId] ?: emptySet())
        }
        .toSet()

    fun hunks(
        filePath: String,
        familySessionIds: () -> Set<String>,
        updatedAtBySession: Map<String, Long>,
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
    ): List<DiffHunk> {
        val orderedSessionIds = familySessionIds().sortedBy { sessionId ->
            -(updatedAtBySession[sessionId] ?: 0L)
        }
        return SessionScopeMergeUtil.mergeByOrderedSessions(filePath, orderedSessionIds, hunksBySessionAndFile)
    }

    fun containsFile(
        filePath: String,
        familySessionIds: () -> Set<String>,
        filesBySession: Map<String, Set<String>>,
    ): Boolean = familySessionIds().any { sessionId ->
        filesBySession[sessionId]?.contains(filePath) == true
    }

    fun previewCandidateSessionIds(
        familySessionIds: () -> Set<String>,
        updatedAtBySession: Map<String, Long>,
    ): List<String> {
        return familySessionIds().sortedByDescending { updatedAtBySession[it] ?: 0L }
    }

    fun listSessions(
        knownSessionIds: Set<String>,
        parentBySessionId: Map<String, String>,
        titleBySessionId: Map<String, String>,
        descriptionBySessionId: Map<String, String>,
        busyBySession: Map<String, Boolean>,
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        addedBySession: Map<String, Set<String>>,
        deletedBySession: Map<String, Set<String>>,
        updatedAtBySession: Map<String, Long>,
    ): List<OpenCodeDiffService.SessionInfo> {
        return knownSessionIds
            .map { sessionId ->
                OpenCodeDiffService.SessionInfo(
                    sessionId = sessionId,
                    parentSessionId = parentBySessionId[sessionId],
                    title = titleBySessionId[sessionId],
                    description = descriptionBySessionId[sessionId],
                    isBusy = busyBySession[sessionId] == true,
                    trackedFileCount = ((hunksBySessionAndFile[sessionId]?.keys ?: emptySet()) +
                        (addedBySession[sessionId] ?: emptySet()) +
                        (deletedBySession[sessionId] ?: emptySet())).size,
                    updatedAtMillis = updatedAtBySession[sessionId] ?: 0L,
                )
            }
            .sortedWith(
                compareByDescending<OpenCodeDiffService.SessionInfo> { it.isBusy }
                    .thenByDescending { it.updatedAtMillis }
                    .thenBy { it.sessionId },
            )
    }
}
