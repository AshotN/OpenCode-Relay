package com.ashotn.opencode.diff

internal object SessionScopeMergeUtil {
    fun mergeByOrderedSessions(
        filePath: String,
        orderedSessionIds: List<String>,
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
    ): List<DiffHunk> {
        val merged = mutableListOf<DiffHunk>()
        val seen = HashSet<String>()
        orderedSessionIds.forEach { sessionId ->
            hunksBySessionAndFile[sessionId]?.get(filePath).orEmpty().forEach { hunk ->
                if (seen.add(DiffTextUtil.hunkFingerprint(hunk))) {
                    merged.add(hunk)
                }
            }
        }
        return merged.sortedBy { it.startLine }
    }
}
