package com.ashotn.opencode.diff

import java.util.concurrent.ConcurrentHashMap

internal class DiffStateStore {
    data class SessionDiffComputedState(
        val nextAfterByFile: MutableMap<String, String>,
        val newHunksByFile: Map<String, List<DiffHunk>>,
        val newDeleted: Set<String>,
        val newAdded: Set<String>,
        val newBaselineByFile: Map<String, String>,
    )

    data class SessionDiffCommitResult(
        val removedFiles: Set<String>,
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
        selectedSessionId = when {
            requestedSessionId == null -> null
            sessionExists(requestedSessionId) -> requestedSessionId
            else -> selectedSessionId
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

        val previousSessionFiles = hunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()

        if (!fromHistory) {
            busyBySession[sessionId] = true
            updatedAtBySession[sessionId] = nowMillis
        }

        hunksBySessionAndFile[sessionId] = computedState.newHunksByFile
        liveHunksBySessionAndFile[sessionId] = if (fromHistory) emptyMap() else computedState.newHunksByFile
        deletedBySession[sessionId] = computedState.newDeleted
        addedBySession[sessionId] = computedState.newAdded
        baselineBeforeBySessionAndFile[sessionId] = computedState.newBaselineByFile

        val newSessionFiles = computedState.newHunksByFile.keys.toSet()
        SessionDiffCommitResult(
            removedFiles = previousSessionFiles - newSessionFiles,
            changedFiles = previousSessionFiles + newSessionFiles,
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
        nowMillis: Long,
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
        updatedAtBySession[sessionId] = nowMillis
        true
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
        pendingTurnFilesBySession.clear()
        diffApplyRevisionBySession.clear()
        selectedSessionId = null
    }
}
