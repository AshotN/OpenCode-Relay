package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.util.TextUtil

internal class EventReducer {
    data class ReconcileDecision(
        val updatedHunks: Map<String, List<DiffHunk>>,
        val updatedDeleted: Set<String>,
        val updatedAdded: Set<String>,
        val removedPaths: Set<String>,
    )

    fun commitSessionBusy(
        stateStore: StateStore,
        stateLock: Any,
        sessionId: String,
        isBusy: Boolean,
        nowMillis: Long,
        generation: Long,
        currentGeneration: () -> Long,
    ): Boolean {
        return stateStore.commitSessionBusy(
            stateLock = stateLock,
            sessionId = sessionId,
            isBusy = isBusy,
            nowMillis = nowMillis,
            expectedGeneration = generation,
            currentGeneration = currentGeneration,
        )
    }

    fun reduceReconcile(
        currentHunks: Map<String, List<DiffHunk>>,
        currentDeleted: Set<String>,
        currentAdded: Set<String>,
        currentBaselines: Map<String, String>,
        readCurrentContent: (String) -> String,
    ): ReconcileDecision? {
        if (currentHunks.isEmpty()) return null

        val updatedHunks = currentHunks.toMutableMap()
        val updatedDeleted = currentDeleted.toMutableSet()
        val updatedAdded = currentAdded.toMutableSet()
        val removedPaths = mutableSetOf<String>()

        for (absPath in currentHunks.keys) {
            val baseline = currentBaselines[absPath] ?: continue
            val diskContent = readCurrentContent(absPath)
            val matchesBaseline =
                TextUtil.normalizeContent(diskContent) == TextUtil.normalizeContent(baseline)
            if (matchesBaseline) {
                updatedHunks.remove(absPath)
                updatedDeleted.remove(absPath)
                updatedAdded.remove(absPath)
                removedPaths.add(absPath)
            }
        }

        if (removedPaths.isEmpty()) return null

        return ReconcileDecision(
            updatedHunks = updatedHunks,
            updatedDeleted = updatedDeleted,
            updatedAdded = updatedAdded,
            removedPaths = removedPaths,
        )
    }
}
