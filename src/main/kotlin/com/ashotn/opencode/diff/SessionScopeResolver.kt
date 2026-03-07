package com.ashotn.opencode.diff

internal class SessionScopeResolver {
    fun knownSessionIds(
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        busyBySession: Map<String, Boolean>,
        updatedAtBySession: Map<String, Long>,
        hierarchySessionIds: Set<String>,
        parentBySessionId: Map<String, String>,
    ): Set<String> {
        val sessionIds = HashSet<String>()
        sessionIds.addAll(hunksBySessionAndFile.keys)
        sessionIds.addAll(busyBySession.keys)
        sessionIds.addAll(updatedAtBySession.keys)
        sessionIds.addAll(hierarchySessionIds)
        sessionIds.addAll(parentBySessionId.keys)
        sessionIds.addAll(parentBySessionId.values)
        return sessionIds
    }

    fun resolveSelectedSessionId(
        selectedSessionId: String?,
        knownSessionIds: Set<String>,
    ): String? {
        if (selectedSessionId != null && selectedSessionId in knownSessionIds) {
            return selectedSessionId
        }
        return null
    }

    fun rootSessionId(sessionId: String, parentBySessionId: Map<String, String>): String {
        val visited = HashSet<String>()
        var current = sessionId
        while (visited.add(current)) {
            val parent = parentBySessionId[current] ?: break
            current = parent
        }
        return current
    }

    fun knownFamilySessionIdsForRoot(rootSessionId: String, parentBySessionId: Map<String, String>): Set<String> {
        val family = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(rootSessionId)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!family.add(current)) continue

            parentBySessionId
                .entries
                .asSequence()
                .filter { it.value == current }
                .map { it.key }
                .forEach { queue.addLast(it) }
        }

        return family
    }

    fun familySessionIds(
        selectedSessionId: String,
        parentBySessionId: Map<String, String>,
        knownSessionIds: Set<String>,
        busyBySession: Map<String, Boolean>,
        updatedAtBySession: Map<String, Long>,
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        nowMillis: Long,
    ): Set<String> {
        val root = rootSessionId(selectedSessionId, parentBySessionId)
        val strictFamily = knownFamilySessionIdsForRoot(root, parentBySessionId)

        val result = strictFamily.toMutableSet()

        val selectedUpdatedAt = updatedAtBySession[selectedSessionId] ?: 0L
        val selectedIsActive =
            (busyBySession[selectedSessionId] == true) ||
                (selectedUpdatedAt > 0L && nowMillis - selectedUpdatedAt <= RECENT_WINDOW_MILLIS)

        if (selectedIsActive) {
            knownSessionIds.forEach { sessionId ->
                if (sessionId in result) return@forEach
                if (parentBySessionId.containsKey(sessionId)) return@forEach

                val updatedAt = updatedAtBySession[sessionId] ?: 0L
                val isBusy = busyBySession[sessionId] == true
                val isRecent = updatedAt > 0L && nowMillis - updatedAt <= RECENT_WINDOW_MILLIS
                val hasDiffState = hunksBySessionAndFile.containsKey(sessionId)

                if (hasDiffState && (isBusy || isRecent)) {
                    result.add(sessionId)
                }
            }
        }

        if (result.isNotEmpty()) return result
        return setOf(selectedSessionId)
    }

    companion object {
        private const val RECENT_WINDOW_MILLIS = 60_000L
    }
}
