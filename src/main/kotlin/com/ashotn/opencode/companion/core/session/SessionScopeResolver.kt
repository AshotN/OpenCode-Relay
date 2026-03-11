package com.ashotn.opencode.companion.core.session

import com.ashotn.opencode.companion.api.session.Session
import com.ashotn.opencode.companion.core.DiffHunk

internal class SessionScopeResolver {
    fun knownSessionIds(
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        busyBySession: Map<String, Boolean>,
        updatedAtBySession: Map<String, Long>,
        sessions: Map<String, Session>,
    ): Set<String> {
        val sessionIds = HashSet<String>()
        sessionIds.addAll(hunksBySessionAndFile.keys)
        sessionIds.addAll(busyBySession.keys)
        sessionIds.addAll(updatedAtBySession.keys)
        sessionIds.addAll(sessions.keys)
        // also include parent IDs so that parent sessions are discoverable
        sessions.values.mapNotNull { it.parentID }.forEach { sessionIds.add(it) }
        return sessionIds
    }

    fun resolveSelectedSessionId(
        selectedSessionId: String?,
        knownSessionIds: Set<String>,
    ): String? = selectedSessionId?.takeIf { it in knownSessionIds }

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
        sessions: Map<String, Session>,
        knownSessionIds: Set<String>,
        busyBySession: Map<String, Boolean>,
        updatedAtBySession: Map<String, Long>,
        hunksBySessionAndFile: Map<String, Map<String, List<DiffHunk>>>,
        nowMillis: Long,
    ): Set<String> {
        val parentBySessionId = sessions.values.mapNotNull { s -> s.parentID?.let { s.id to it } }.toMap()
        val root = rootSessionId(selectedSessionId, parentBySessionId)
        val strictFamily = knownFamilySessionIdsForRoot(root, parentBySessionId)

        val result = strictFamily.toMutableSet()

        val selectedUpdatedAt = updatedAtBySession[selectedSessionId] ?: 0L
        val selectedIsRoot = !parentBySessionId.containsKey(selectedSessionId)
        val selectedIsActive =
            (busyBySession[selectedSessionId] == true) ||
                    (selectedUpdatedAt > 0L && nowMillis - selectedUpdatedAt <= RECENT_WINDOW_MILLIS) ||
                    (selectedIsRoot && knownSessionIds.any { sid ->
                        sid !in result &&
                                parentBySessionId[sid].let { it == null || it == selectedSessionId } &&
                                (busyBySession[sid] == true ||
                                        (updatedAtBySession[sid]
                                            ?: 0L).let { it > 0L && nowMillis - it <= RECENT_WINDOW_MILLIS }) &&
                                hunksBySessionAndFile.containsKey(sid)
                    })

        if (selectedIsActive) {
            knownSessionIds.forEach { sessionId ->
                if (sessionId in result) return@forEach
                if (parentBySessionId[sessionId].let { it != null && it !in result }) return@forEach

                // Skip independent sessions that belong to a different conversation.
                val isKnownRoot = !parentBySessionId.containsKey(sessionId) &&
                        (updatedAtBySession[sessionId] ?: 0L) > 0L
                if (isKnownRoot && sessionId !in result) return@forEach

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