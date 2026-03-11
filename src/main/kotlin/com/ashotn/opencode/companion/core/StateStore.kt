package com.ashotn.opencode.companion.core

import java.util.concurrent.ConcurrentHashMap

internal class StateStore {
    data class SessionDiffComputedState(
        val nextAfterByFile: MutableMap<String, String>,
        val processedPaths: Set<String>, // Paths touched by the current apply pass.
        val newHunksByFile: Map<String, List<DiffHunk>>,
        val newDeleted: Set<String>,
        val newAdded: Set<String>,
        val newBaselineByFile: Map<String, String>,
        val newServerAfterByFile: Map<String, String>,
    )

    data class SessionDiffCommitResult(
        val changedFiles: Set<String>,
    )

    @Volatile
    var selectedSessionId: String? = null


    val busyBySession = ConcurrentHashMap<String, Boolean>()
    val updatedAtBySession = ConcurrentHashMap<String, Long>()

    val hunksBySessionAndFile = ConcurrentHashMap<String, Map<String, List<DiffHunk>>>()
    val liveHunksBySessionAndFile = ConcurrentHashMap<String, Map<String, List<DiffHunk>>>()
    val deletedBySession = ConcurrentHashMap<String, Set<String>>()
    val addedBySession = ConcurrentHashMap<String, Set<String>>()
    val baselineBeforeBySessionAndFile = ConcurrentHashMap<String, Map<String, String>>()
    val lastAfterBySessionAndFile = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** The server's intended "after AI" content per file per session, sourced from SessionDiffFile.after. */
    val serverAfterBySessionAndFile = ConcurrentHashMap<String, Map<String, String>>()
    val pendingTurnFilesBySession = ConcurrentHashMap<String, Set<String>>()

    private val diffApplyRevisionBySession = ConcurrentHashMap<String, Long>()

    fun reserveRevisionForSessionDiffApply(
        stateLock: Any,
        sessionId: String,
        fromHistory: Boolean,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): Long? = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized null
        }
        val currentRevision = diffApplyRevisionBySession[sessionId] ?: 0L
        if (fromHistory && currentRevision > 0L) {
            return@synchronized null
        }
        val nextRevision = currentRevision + 1
        diffApplyRevisionBySession[sessionId] = nextRevision
        nextRevision
    }

    data class ReconcileSnapshot(
        val currentHunks: Map<String, List<DiffHunk>>,
        val currentDeleted: Set<String>,
        val currentAdded: Set<String>,
        val currentBaselines: Map<String, String>,
    )

    data class SessionDiffPrepareSnapshot(
        val previousAfterByFile: Map<String, String>,
    )

    private data class SessionStateSnapshot(
        val hunks: Map<String, List<DiffHunk>>,
        val liveHunks: Map<String, List<DiffHunk>>,
        val deleted: Set<String>,
        val added: Set<String>,
        val baselines: Map<String, String>,
    )

    fun currentSessionRevision(stateLock: Any, sessionId: String): Long = synchronized(stateLock) {
        diffApplyRevisionBySession[sessionId] ?: 0L
    }

    fun snapshotSessionDiffPrepareState(
        stateLock: Any,
        sessionId: String,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): SessionDiffPrepareSnapshot? = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized null
        }
        SessionDiffPrepareSnapshot(
            previousAfterByFile = (lastAfterBySessionAndFile[sessionId] ?: emptyMap()).toMap(),
        )
    }

    fun snapshotSessionReconcileState(
        stateLock: Any,
        sessionId: String,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): ReconcileSnapshot? = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized null
        }
        val currentHunks = hunksBySessionAndFile[sessionId] ?: return@synchronized null
        if (currentHunks.isEmpty()) return@synchronized null

        ReconcileSnapshot(
            currentHunks = currentHunks.toMap(),
            currentDeleted = (deletedBySession[sessionId] ?: emptySet()).toSet(),
            currentAdded = (addedBySession[sessionId] ?: emptySet()).toSet(),
            currentBaselines = (baselineBeforeBySessionAndFile[sessionId] ?: emptyMap()).toMap(),
        )
    }

    fun commitTurnPatch(
        stateLock: Any,
        sessionId: String,
        touchedPaths: Set<String>,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): Boolean = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized false
        }
        pendingTurnFilesBySession[sessionId] = touchedPaths
        true
    }

    fun consumeTurnScopeForDiff(
        stateLock: Any,
        sessionId: String,
        fromHistory: Boolean,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): Set<String>? = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized null
        }
        if (fromHistory) return@synchronized null
        pendingTurnFilesBySession.remove(sessionId)
    }

    fun commitSessionBusy(
        stateLock: Any,
        sessionId: String,
        isBusy: Boolean,
        nowMillis: Long,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): Boolean = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized false
        }
        busyBySession[sessionId] = isBusy
        updatedAtBySession[sessionId] = nowMillis
        true
    }

    fun commitSelectedSession(
        stateLock: Any,
        requestedSessionId: String?,
        sessionExists: (String) -> Boolean,
    ): String? = synchronized(stateLock) {
        when {
            requestedSessionId == null -> {
                selectedSessionId = null
            }

            sessionExists(requestedSessionId) -> {
                selectedSessionId = requestedSessionId
            }
            // requested session not yet known — leave existing selection untouched
        }
        selectedSessionId
    }

    fun resolveSelectedSessionId(
        stateLock: Any,
        resolver: (selectedSessionId: String?) -> String?,
    ): String? = synchronized(stateLock) {
        val resolved = resolver(selectedSessionId)
        selectedSessionId = resolved
        resolved
    }

    fun commitSessionDiffApply(
        stateLock: Any,
        sessionId: String,
        revision: Long,
        fromHistory: Boolean,
        computedState: SessionDiffComputedState,
        nowMillis: Long,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): SessionDiffCommitResult? = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized null
        }
        if (revision != (diffApplyRevisionBySession[sessionId] ?: 0L)) {
            return@synchronized null
        }

        lastAfterBySessionAndFile[sessionId] = computedState.nextAfterByFile

        // Merge server-intended "after AI" content — kept for diff viewer display.
        val previousServerAfter = serverAfterBySessionAndFile[sessionId] ?: emptyMap()
        serverAfterBySessionAndFile[sessionId] = mergeMapByProcessedPaths(
            previous = previousServerAfter,
            processedPaths = computedState.processedPaths,
            next = computedState.newServerAfterByFile,
            replaceAll = fromHistory,
        )

        val previousState = SessionStateSnapshot(
            hunks = hunksBySessionAndFile[sessionId] ?: emptyMap(),
            liveHunks = liveHunksBySessionAndFile[sessionId] ?: emptyMap(),
            deleted = deletedBySession[sessionId] ?: emptySet(),
            added = addedBySession[sessionId] ?: emptySet(),
            baselines = baselineBeforeBySessionAndFile[sessionId] ?: emptyMap(),
        )

        if (!fromHistory) {
            busyBySession[sessionId] = true
            updatedAtBySession[sessionId] = nowMillis
        }

        val mergedState = SessionStateSnapshot(
            hunks = mergeMapByProcessedPaths(
                previous = previousState.hunks,
                processedPaths = computedState.processedPaths,
                next = computedState.newHunksByFile,
                replaceAll = fromHistory,
            ),
            liveHunks = if (fromHistory) {
                emptyMap()
            } else {
                // Live hunks always replace entirely — they represent only the current turn's
                // changes. Files from previous turns must not carry over here, otherwise the
                // editor continues to show green/red highlights for files the current turn
                // never touched.
                computedState.newHunksByFile
            },
            deleted = mergeSetByProcessedPaths(
                previous = previousState.deleted,
                processedPaths = computedState.processedPaths,
                next = computedState.newDeleted,
                replaceAll = fromHistory,
            ),
            added = mergeSetByProcessedPaths(
                previous = previousState.added,
                processedPaths = computedState.processedPaths,
                next = computedState.newAdded,
                replaceAll = fromHistory,
            ),
            baselines = mergeMapByProcessedPaths(
                previous = previousState.baselines,
                processedPaths = computedState.processedPaths,
                next = computedState.newBaselineByFile,
                replaceAll = fromHistory,
            ),
        )

        hunksBySessionAndFile[sessionId] = mergedState.hunks
        liveHunksBySessionAndFile[sessionId] = mergedState.liveHunks
        deletedBySession[sessionId] = mergedState.deleted
        addedBySession[sessionId] = mergedState.added
        baselineBeforeBySessionAndFile[sessionId] = mergedState.baselines

        val previousTrackedFiles = previousState.hunks.keys + previousState.added + previousState.deleted
        val newTrackedFiles = mergedState.hunks.keys + mergedState.added + mergedState.deleted

        SessionDiffCommitResult(
            changedFiles = previousTrackedFiles + newTrackedFiles,
        )
    }

    fun commitReconcile(
        stateLock: Any,
        sessionId: String,
        revision: Long,
        updatedHunks: Map<String, List<DiffHunk>>,
        updatedDeleted: Set<String>,
        updatedAdded: Set<String>,
        currentBaselines: Map<String, String>,
        expectedGeneration: Long,
        currentGeneration: () -> Long,
    ): Boolean = synchronized(stateLock) {
        if (expectedGeneration != currentGeneration()) {
            return@synchronized false
        }
        if (revision != (diffApplyRevisionBySession[sessionId] ?: 0L)) {
            return@synchronized false
        }

        hunksBySessionAndFile[sessionId] = updatedHunks
        liveHunksBySessionAndFile[sessionId] =
            (liveHunksBySessionAndFile[sessionId] ?: emptyMap())
                .filterKeys { it in updatedHunks.keys }
        deletedBySession[sessionId] = updatedDeleted
        addedBySession[sessionId] = updatedAdded
        baselineBeforeBySessionAndFile[sessionId] = currentBaselines.filterKeys { it in updatedHunks.keys }
        true
    }

    private fun <T> mergeMapByProcessedPaths(
        previous: Map<String, T>,
        processedPaths: Set<String>,
        next: Map<String, T>,
        replaceAll: Boolean,
    ): Map<String, T> {
        if (replaceAll) return next
        return previous.filterKeys { it !in processedPaths } + next
    }

    private fun mergeSetByProcessedPaths(
        previous: Set<String>,
        processedPaths: Set<String>,
        next: Set<String>,
        replaceAll: Boolean,
    ): Set<String> {
        if (replaceAll) return next
        return (previous - processedPaths) + next
    }

    fun resetState() {
        busyBySession.clear()
        updatedAtBySession.clear()
        hunksBySessionAndFile.clear()
        liveHunksBySessionAndFile.clear()
        deletedBySession.clear()
        addedBySession.clear()
        baselineBeforeBySessionAndFile.clear()
        lastAfterBySessionAndFile.clear()
        serverAfterBySessionAndFile.clear()
        pendingTurnFilesBySession.clear()
        diffApplyRevisionBySession.clear()
        selectedSessionId = null
    }
}
