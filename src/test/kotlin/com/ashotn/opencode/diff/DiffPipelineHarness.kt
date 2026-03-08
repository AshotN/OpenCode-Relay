package com.ashotn.opencode.diff

import com.ashotn.opencode.diff.NoOpDiffTracer
import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus

/**
 * Self-contained harness for testing the diff pipeline without the IntelliJ platform.
 *
 * Usage:
 * ```
 * val h = DiffPipelineHarness()
 * h.disk[h.abs("note.md")] = "content\n"
 * h.commitTurnPatch(listOf("note.md"))
 * h.applySessionDiff(listOf("note.md" to SessionDiffStatus.MODIFIED))
 * ```
 */
internal class DiffPipelineHarness(
    val projectBase: String = "/project",
    val sessionId: String = "ses_test",
    val generation: Long = 1L,
    hunkComputer: ((com.ashotn.opencode.ipc.FileDiff, String) -> List<DiffHunk>)? = null,
) {
    /** Simulated disk: keyed by absolute path. */
    val disk = mutableMapOf<String, String>()

    val stateStore = DiffStateStore()
    val stateLock = Any()
    val eventReducer = DiffEventReducer()

    private val computer = SessionDiffApplyComputer(
        contentReader = { absPath -> disk[absPath] ?: "" },
        hunkComputer = hunkComputer ?: { fileDiff, sid ->
            // Default fake: emit one hunk whenever before != after
            if (fileDiff.before == fileDiff.after) emptyList()
            else listOf(DiffHunk(
                fileDiff.file, 0,
                if (fileDiff.before.isEmpty()) emptyList() else listOf(fileDiff.before),
                if (fileDiff.after.isEmpty()) emptyList() else listOf(fileDiff.after),
                sid,
            ))
        },
        log = NoOpLogger,
        tracer = NoOpDiffTracer,
    )

    /** Converts a relative path to an absolute path under [projectBase]. */
    fun abs(relPath: String): String = "$projectBase/$relPath"

    fun commitTurnPatch(relPaths: List<String>) {
        eventReducer.commitTurnPatch(
            stateStore = stateStore,
            stateLock = stateLock,
            sessionId = sessionId,
            touchedPaths = relPaths.map { abs(it) }.toSet(),
            generation = generation,
            currentGeneration = { generation },
        )
    }

    fun applySessionDiff(
        files: List<Pair<String, SessionDiffStatus>>,
        serverAfterByFile: Map<String, String> = emptyMap(),
    ): DiffStateStore.SessionDiffCommitResult? {
        val decision = eventReducer.beginSessionDiffApply(
            stateStore = stateStore,
            stateLock = stateLock,
            sessionId = sessionId,
            fromHistory = false,
            generation = generation,
            currentGeneration = { generation },
        )
        if (!decision.shouldApply) return null
        val revision = decision.revision!!
        val turnScope = decision.turnScope

        val prepareSnapshot = stateStore.snapshotSessionDiffPrepareState(
            stateLock = stateLock,
            sessionId = sessionId,
            expectedGeneration = generation,
            currentGeneration = { generation },
        ) ?: return null

        val event = OpenCodeEvent.SessionDiff(
            sessionId = sessionId,
            files = files.map { (relPath, status) ->
                OpenCodeEvent.SessionDiffFile(
                    file = abs(relPath),
                    before = "",
                    after = serverAfterByFile[abs(relPath)] ?: "",
                    additions = 0,
                    deletions = 0,
                    status = status,
                )
            },
        )

        val computedState = computer.compute(
            projectBase = projectBase,
            event = event,
            fromHistory = false,
            turnScope = turnScope,
            previousAfterByFile = prepareSnapshot.previousAfterByFile,
        )

        return stateStore.commitSessionDiffApply(
            stateLock = stateLock,
            sessionId = sessionId,
            revision = revision,
            fromHistory = false,
            computedState = computedState,
            nowMillis = 0L,
            expectedGeneration = generation,
            currentGeneration = { generation },
        )
    }

    // --- Convenience accessors into stateStore ---

    fun hunkFiles(): Set<String> =
        stateStore.hunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()

    fun liveHunkFiles(): Set<String> =
        stateStore.liveHunksBySessionAndFile[sessionId]?.keys?.toSet() ?: emptySet()

    fun addedFiles(): Set<String> =
        stateStore.addedBySession[sessionId] ?: emptySet()

    fun deletedFiles(): Set<String> =
        stateStore.deletedBySession[sessionId] ?: emptySet()

    /** Mirrors the trackedFileCount logic in DiffQueryService.listSessions. */
    fun trackedFileCount(): Int =
        ((stateStore.hunksBySessionAndFile[sessionId]?.keys ?: emptySet()) +
            (stateStore.addedBySession[sessionId] ?: emptySet()) +
            (stateStore.deletedBySession[sessionId] ?: emptySet())).size

    fun hunksFor(relPath: String): List<DiffHunk> =
        stateStore.hunksBySessionAndFile[sessionId]?.get(abs(relPath)) ?: emptyList()

    fun baseline(relPath: String): String? =
        stateStore.baselineBeforeBySessionAndFile[sessionId]?.get(abs(relPath))

    fun serverAfter(relPath: String): String? =
        stateStore.serverAfterBySessionAndFile[sessionId]?.get(abs(relPath))
}
