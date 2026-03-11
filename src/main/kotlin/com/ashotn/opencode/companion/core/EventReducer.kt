package com.ashotn.opencode.companion.core

import com.ashotn.opencode.companion.util.TextUtil
import com.ashotn.opencode.companion.util.toAbsolutePath

internal class EventReducer {
    enum class SessionDiffSkipReason {
        UNSCOPED_LIVE,
        STALE_OR_ALREADY_LOADED,
    }

    private data class SessionDiffScopeDecision(
        val shouldApply: Boolean,
        val turnScope: Set<String>?,
    )

    data class SessionDiffApplyDecision(
        val shouldApply: Boolean,
        val turnScope: Set<String>?,
        val revision: Long?,
        val skipReason: SessionDiffSkipReason? = null,
    )

    data class ReconcileDecision(
        val updatedHunks: Map<String, List<DiffHunk>>,
        val updatedDeleted: Set<String>,
        val updatedAdded: Set<String>,
        val removedPaths: Set<String>,
    )

    fun reduceTurnPatchTouchedPaths(projectBase: String, files: List<String>): Set<String> =
        files.map { path -> toAbsolutePath(projectBase, path) }.toSet()

    fun commitTurnPatch(
        stateStore: StateStore,
        stateLock: Any,
        sessionId: String,
        touchedPaths: Set<String>,
        generation: Long,
        currentGeneration: () -> Long,
    ): Boolean {
        return stateStore.commitTurnPatch(
            stateLock = stateLock,
            sessionId = sessionId,
            touchedPaths = touchedPaths,
            expectedGeneration = generation,
            currentGeneration = currentGeneration,
        )
    }

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

    private fun reduceSessionDiffScope(
        stateStore: StateStore,
        stateLock: Any,
        sessionId: String,
        fromHistory: Boolean,
        generation: Long,
        currentGeneration: () -> Long,
    ): SessionDiffScopeDecision {
        val turnScope = stateStore.consumeTurnScopeForDiff(
            stateLock = stateLock,
            sessionId = sessionId,
            fromHistory = fromHistory,
            expectedGeneration = generation,
            currentGeneration = currentGeneration,
        )
        if (!fromHistory && turnScope == null) {
            return SessionDiffScopeDecision(shouldApply = false, turnScope = null)
        }
        return SessionDiffScopeDecision(shouldApply = true, turnScope = turnScope)
    }

    fun beginSessionDiffApply(
        stateStore: StateStore,
        stateLock: Any,
        sessionId: String,
        fromHistory: Boolean,
        generation: Long,
        currentGeneration: () -> Long,
    ): SessionDiffApplyDecision {
        val scopeDecision = reduceSessionDiffScope(
            stateStore = stateStore,
            stateLock = stateLock,
            sessionId = sessionId,
            fromHistory = fromHistory,
            generation = generation,
            currentGeneration = currentGeneration,
        )
        if (!scopeDecision.shouldApply) {
            return SessionDiffApplyDecision(
                shouldApply = false,
                turnScope = null,
                revision = null,
                skipReason = SessionDiffSkipReason.UNSCOPED_LIVE,
            )
        }

        val revision = stateStore.reserveRevisionForSessionDiffApply(
            stateLock = stateLock,
            sessionId = sessionId,
            fromHistory = fromHistory,
            expectedGeneration = generation,
            currentGeneration = currentGeneration,
        )
        if (revision == null) {
            return SessionDiffApplyDecision(
                shouldApply = false,
                turnScope = scopeDecision.turnScope,
                revision = null,
                skipReason = SessionDiffSkipReason.STALE_OR_ALREADY_LOADED,
            )
        }

        return SessionDiffApplyDecision(
            shouldApply = true,
            turnScope = scopeDecision.turnScope,
            revision = revision,
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
                // Clear hunks so inline editor highlights are removed, but keep the
                // file key in updatedHunks with an empty list so it remains visible
                // in the session file list and the 3-panel diff viewer stays accessible.
                updatedHunks[absPath] = emptyList()
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