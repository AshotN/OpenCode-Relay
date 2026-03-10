package com.ashotn.opencode.companion.diff

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

    /**
     * Returns inline hunks for [filePath] from the live (latest-turn-only) state.
     *
     * Per the inline diff policy (docs/INLINE_DIFF_POLICY.md):
     *   - Only the selected session may render inline highlights (Rule 1).
     *   - Inline highlights show only the most recent turn (Rule 2).
     *
     * Live hunks are stored per-session and always represent only the latest
     * committed turn — they are replaced entirely on each new turn and cleared
     * to empty on fromHistory applies. There is no cross-session merge here:
     * only the selected session's own live hunks are returned.
     */
    fun liveHunks(
        filePath: String,
        selectedSessionId: () -> String?,
        liveHunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
    ): List<DiffHunk> {
        val sessionId = selectedSessionId() ?: return emptyList()
        return liveHunksBySessionAndFile[sessionId]?.get(filePath) ?: emptyList()
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
        return familySessionIds().sortedBy { updatedAtBySession[it] ?: 0L }
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
        sessionIdsWithMessages: Set<String>,
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
                    hasMessages = sessionId in sessionIdsWithMessages,
                )
            }
            .sortedWith(
                compareByDescending<OpenCodeDiffService.SessionInfo> { it.isBusy }
                    .thenByDescending { it.updatedAtMillis }
                    .thenBy { it.sessionId },
            )
    }
}
