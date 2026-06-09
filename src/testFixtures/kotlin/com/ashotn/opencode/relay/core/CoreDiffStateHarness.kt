package com.ashotn.opencode.relay.core

import com.ashotn.opencode.relay.api.session.Session
import com.ashotn.opencode.relay.api.session.SessionDiffSnapshot
import com.ashotn.opencode.relay.core.session.SessionScopeResolver

/**
 * JVM-only harness for applying real OpenCode diff payloads to the plugin's core
 * diff state without requiring an IntelliJ Project service.
 */
class CoreDiffStateHarness(
    private val projectBase: String,
    private val generation: Long = 1L,
) {
    data class VisibleState(
        val visibleFiles: Set<String>,
        val liveVisibleFiles: Set<String>,
        val liveHunksByFile: Map<String, List<DiffHunk>>,
    )

    private val stateStore = StateStore()
    private val stateLock = Any()
    private val eventReducer = EventReducer()
    private val scopeResolver = SessionScopeResolver()
    private val queryService = QueryService()

    private val computer = SessionDiffApplyComputer(
        contentReader = { absPath -> contentReader(absPath) },
        hunkComputer = { fileDiff, sid ->
            if (fileDiff.before == fileDiff.after) emptyList()
            else listOf(
                DiffHunk(
                    filePath = fileDiff.file,
                    startLine = 0,
                    removedLines = contentLines(fileDiff.before),
                    addedLines = contentLines(fileDiff.after),
                    sessionId = sid,
                )
            )
        },
        log = NoOpLogger,
        tracer = NoOpDiffTracer,
    )

    private var contentReader: (String) -> String = { "" }

    fun applyLiveMessageDiff(
        sessionId: String,
        diff: SessionDiffSnapshot,
        readContent: (String) -> String,
    ) {
        contentReader = readContent
        val revision = stateStore.reserveRevisionForSessionDiffApply(
            stateLock = stateLock,
            sessionId = sessionId,
            expectedGeneration = generation,
            currentGeneration = { generation },
        )
        check(revision != null) { "core state skipped live diff for $sessionId" }
        val computedState = computer.compute(
            projectBase = projectBase,
            event = diff,
            fromHistory = false,
        )
        val commitResult = stateStore.commitSessionDiffApply(
            stateLock = stateLock,
            sessionId = sessionId,
            revision = revision,
            fromHistory = false,
            computedState = computedState,
            nowMillis = System.currentTimeMillis(),
            expectedGeneration = generation,
            currentGeneration = { generation },
        )
        check(commitResult != null) { "core state skipped live diff commit for $sessionId" }
    }

    fun selectRootAndVisibleState(rootSessionId: String, sessions: List<Session>): VisibleState {
        val sessionsById = sessions.associateBy { it.id }
        val knownSessionIds = scopeResolver.knownSessionIds(
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            busyBySession = stateStore.busyBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
            sessions = sessionsById,
        ) + rootSessionId
        stateStore.commitSelectedSession(
            stateLock = stateLock,
            requestedSessionId = rootSessionId,
            sessionExists = { candidate -> candidate in knownSessionIds },
        )
        val familySessionIds = scopeResolver.familySessionIds(
            selectedSessionId = rootSessionId,
            sessions = sessionsById,
            knownSessionIds = knownSessionIds,
            busyBySession = stateStore.busyBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            nowMillis = System.currentTimeMillis(),
        )
        val liveVisibleFiles = queryService.liveVisibleFiles(
            familySessionIds = { familySessionIds },
            liveHunksBySessionAndFile = stateStore.liveHunksBySessionAndFile,
            addedBySession = stateStore.addedBySession,
            deletedBySession = stateStore.deletedBySession,
        )
        return VisibleState(
            visibleFiles = queryService.visibleFiles(
                familySessionIds = { familySessionIds },
                hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
                addedBySession = stateStore.addedBySession,
                deletedBySession = stateStore.deletedBySession,
            ),
            liveVisibleFiles = liveVisibleFiles,
            liveHunksByFile = liveVisibleFiles.associateWith { filePath ->
                queryService.liveHunks(
                    filePath = filePath,
                    familySessionIds = { familySessionIds },
                    liveHunksBySessionAndFile = stateStore.liveHunksBySessionAndFile,
                )
            },
        )
    }

    private fun contentLines(content: String): List<String> = if (content.isEmpty()) emptyList() else content.lines()
}
