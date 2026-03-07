package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.OpenCodeEvent
import com.ashotn.opencode.ipc.SessionDiffStatus
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the diff pipeline using [DiffPipelineHarness].
 *
 * Each test covers a distinct behaviour or regression. The harness simulates the full
 * pipeline (turn.patch → session.diff → state commit) without requiring the IntelliJ
 * platform — disk content is a plain in-memory map and hunk computation is a fake.
 */
class SessionDiffPipelineTest {

    private val h = DiffPipelineHarness()

    // -------------------------------------------------------------------------
    // A file in turnScope that is absent from the server's session.diff payload
    // must be treated as resolved back to baseline — its hunks and lastAfter
    // must be cleared, not left stale. If they are left stale, the next turn
    // will compute its diff against wrong content and show incorrect changes.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to write some content to a file.
    //   2. In a new turn, ask the AI to delete all content from that file.
    //      No diff should be visible in the editor after this turn.
    //   3. In a new turn, ask the AI to write new content to the same file.
    //      Only a green addition should be visible — no red deletion.
    // -------------------------------------------------------------------------
    @Test
    fun `empty server diff for scoped file should clear existing hunks`() {
        val file = "note.md"

        // Turn 1: add content
        h.disk[h.abs(file)] = "line1\n"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file)), h.hunkFiles(), "turn 1: file should be tracked")

        // Turn 2: add more
        h.disk[h.abs(file)] = "line1\nline2\n"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file)), h.hunkFiles(), "turn 2: file should still be tracked")

        // Turn 3: remove all — server sends 0 files in session.diff
        h.disk[h.abs(file)] = ""
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(emptyList())

        assertTrue(h.hunkFiles().isEmpty(), "turn 3: hunkFiles should be empty after empty diff for scoped file, got: ${h.hunkFiles()}")
        assertTrue(h.liveHunkFiles().isEmpty(), "turn 3: liveHunkFiles should be empty")

        // Turn 4: add content again — baseline must be "" not stale "line1\nline2\n"
        h.disk[h.abs(file)] = "line3\n"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))

        assertEquals(setOf(h.abs(file)), h.hunkFiles(), "turn 4: file should be tracked again")
        assertEquals("", h.baseline(file), "turn 4: baseline should be '' (reset in turn 3), not stale lastAfter")
    }

    // -------------------------------------------------------------------------
    // trackedFileCount must reflect all files the session has touched —
    // including ADDED and DELETED files that carry no hunks. Hunks only exist
    // for files with content changes; a newly created empty file has no hunks
    // but is still a tracked file and must be counted.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to create a new empty file.
    //   2. The session in the tool window should show (1), not (0).
    // -------------------------------------------------------------------------
    @Test
    fun `added file with no content should count toward trackedFileCount`() {
        val file = "note.md"
        h.disk[h.abs(file)] = ""
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.ADDED))

        assertEquals(setOf(h.abs(file)), h.addedFiles(), "file should be in addedFiles")
        assertTrue(h.hunkFiles().isEmpty(), "no hunks for empty new file")
        assertEquals(1, h.trackedFileCount(), "trackedFileCount must count added files, not just hunk files")
    }

    // -------------------------------------------------------------------------
    // A hunk for a newly created file must have empty removedLines. The file
    // did not exist before, so there is nothing to show as deleted. Any
    // non-empty removedLines would render as a red inlay block in the editor,
    // obscuring or replacing the green addition highlight.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to create a new file and write content into it.
    //   2. Open the file — only green highlighting should be visible, no red
    //      inlay block above the added lines.
    // -------------------------------------------------------------------------
    @Test
    fun `added file with content should produce only addedLines, no removedLines`() {
        val file = "note.md"

        // Turn 1: file created empty — no hunk expected
        h.disk[h.abs(file)] = ""
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.ADDED))
        assertTrue(h.hunksFor(file).isEmpty(), "turn 1: no hunk for empty file")

        // Turn 2: content written — hunk must have no removedLines
        h.disk[h.abs(file)] = "1"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.ADDED))

        val hunks = h.hunksFor(file)
        assertEquals(1, hunks.size, "turn 2: expected one hunk")
        val hunk = hunks.single()
        assertTrue(hunk.removedLines.isEmpty(), "removedLines must be empty for a new file addition, got: ${hunk.removedLines}")
        assertEquals(listOf("1"), hunk.addedLines, "addedLines should contain the new content")
    }

    // -------------------------------------------------------------------------
    // A session.diff with no preceding turn.patch must be ignored. turn.patch
    // establishes the scope of files the current turn is allowed to touch; without
    // it there is no safe boundary and applying the diff could corrupt state for
    // files the current turn never intended to modify.
    //
    // MANUAL VERIFICATION: No visible UI effect. With OPENCODE_DIFF_TRACE=1
    // the skipped event will log skipReason=UNSCOPED_LIVE in the trace file.
    // -------------------------------------------------------------------------
    @Test
    fun `session diff without turn patch is ignored`() {
        val file = "note.md"
        h.disk[h.abs(file)] = "content\n"
        val result = h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertNull(result, "diff without turn scope should be skipped")
        assertTrue(h.hunkFiles().isEmpty(), "no hunks without turn scope")
    }

    // -------------------------------------------------------------------------
    // When the AI delegates work to parallel sub-agents, every file touched by
    // any sub-agent must show a green highlight in the editor. All files must
    // be visible simultaneously, not just the file from the last sub-agent to
    // finish.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to spin up 5 sub-agents, each creating a different file with some content.
    //   2. All 5 files should show a green addition highlight simultaneously.
    //   3. If only one file shows green, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `parallel sub-agent files are all visible simultaneously`() {
        val projectBase = "/project"
        val generation = 1L
        val rootSession = "ses_root"
        val childSessions = (1..5).map { "ses_child$it" }
        val files = childSessions.mapIndexed { i, sid -> sid to "notes/note${i + 1}.md" }

        val stateStore = DiffStateStore()
        val stateLock = Any()
        val eventReducer = DiffEventReducer()
        val disk = mutableMapOf<String, String>()

        val computer = SessionDiffApplyComputer(
            contentReader = { absPath -> disk[absPath] ?: "" },
            hunkComputer = { fileDiff, sid ->
                if (fileDiff.before == fileDiff.after) emptyList()
                else listOf(
                    DiffHunk(fileDiff.file, 0,
                        emptyList(),
                        if (fileDiff.after.isEmpty()) emptyList() else listOf(fileDiff.after),
                        sid)
                )
            },
            log = NoOpLogger,
            tracer = NoOpDiffTracer,
        )

        // Each child session does its own turn.patch + session.diff for its one file.
        for ((childSessionId, relPath) in files) {
            val absPath = "$projectBase/$relPath"
            disk[absPath] = "# Note\n"

            val touchedPaths = setOf(absPath)
            stateStore.commitTurnPatch(
                stateLock = stateLock,
                sessionId = childSessionId,
                touchedPaths = touchedPaths,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )

            val revision = stateStore.reserveRevisionForSessionDiffApply(
                stateLock = stateLock,
                sessionId = childSessionId,
                fromHistory = false,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!

            val turnScope = stateStore.consumeTurnScopeForDiff(
                stateLock = stateLock,
                sessionId = childSessionId,
                fromHistory = false,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )

            val prepareSnapshot = stateStore.snapshotSessionDiffPrepareState(
                stateLock = stateLock,
                sessionId = childSessionId,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!

            val event = OpenCodeEvent.SessionDiff(
                sessionId = childSessionId,
                files = listOf(
                    OpenCodeEvent.SessionDiffFile(
                        file = absPath,
                        before = "",
                        after = "",
                        additions = 1,
                        deletions = 0,
                        status = SessionDiffStatus.ADDED,
                    )
                ),
            )

            val computedState = computer.compute(
                projectBase = projectBase,
                event = event,
                fromHistory = false,
                turnScope = turnScope,
                previousAfterByFile = prepareSnapshot.previousAfterByFile,
            )

            stateStore.commitSessionDiffApply(
                stateLock = stateLock,
                sessionId = childSessionId,
                revision = revision,
                fromHistory = false,
                computedState = computedState,
                nowMillis = 0L,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )
        }

        val allExpectedFiles = files.map { (_, rel) -> "$projectBase/$rel" }.toSet()

        // Simulate: hierarchy refresh has NOT returned yet — parentBySessionId is empty.
        // With no hierarchy, the root session has no children, so its family = {root} only,
        // and root has no liveHunks. The selected child session family = {child} only,
        // so only that child's one file is visible.
        val parentBySessionId = ConcurrentHashMap<String, String>() // empty — race condition

        val scopeResolver = SessionScopeResolver()
        val queryService = DiffQueryService()

        // Select the root session (user is viewing the parent conversation).
        stateStore.commitSelectedSession(
            stateLock = stateLock,
            requestedSessionId = rootSession,
            sessionExists = { true },
        )

        val familyWithoutHierarchy = scopeResolver.familySessionIds(
            selectedSessionId = rootSession,
            parentBySessionId = parentBySessionId,
            knownSessionIds = (childSessions + rootSession).toSet(),
            busyBySession = stateStore.busyBySession,
            updatedAtBySession = stateStore.updatedAtBySession,
            hunksBySessionAndFile = stateStore.hunksBySessionAndFile,
            nowMillis = 0L,
        )

        val liveVisibleWithoutHierarchy = queryService.liveVisibleFiles(
            familySessionIds = { familyWithoutHierarchy },
            liveHunksBySessionAndFile = stateStore.liveHunksBySessionAndFile,
            addedBySession = stateStore.addedBySession,
            deletedBySession = stateStore.deletedBySession,
        )

        // All 5 child files must be visible even before hierarchy refresh completes.
        // Currently fails: without parentBySessionId the family resolves to {root} only,
        // root has no liveHunks, so nothing is visible.
        assertEquals(
            allExpectedFiles,
            liveVisibleWithoutHierarchy,
            "All child-session files must be visible under the root session even before hierarchy refresh, got: $liveVisibleWithoutHierarchy",
        )
    }

    // -------------------------------------------------------------------------
    // Each turn's diff must be computed relative to the file content at the
    // start of that turn, not the original file. The pipeline advances the
    // baseline (effectiveBefore) to lastAfter after each turn. If it does not,
    // lines changed in earlier turns will appear highlighted in later turns.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to append a line to a file, then append another in a second turn.
    //   2. After turn 2, only the line from turn 2 should be highlighted green —
    //      the line from turn 1 should no longer be highlighted.
    // -------------------------------------------------------------------------
    @Test
    fun `multi-turn baseline advances correctly`() {
        val file = "note.md"

        h.disk[h.abs(file)] = "line1\n"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals("", h.baseline(file), "turn 1 baseline should be empty (file was new)")

        h.disk[h.abs(file)] = "line1\nline2\n"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals("line1\n", h.baseline(file), "turn 2 baseline should be turn 1's final content")
    }

    // -------------------------------------------------------------------------
    // When you double-click a file in the diff viewer after the AI has modified
    // it across multiple turns (e.g. add poem, then add signature), the diff
    // must show all changes from the original file — not just the most recent
    // turn's change. The "before" side of the diff must always be the content
    // the file had before the AI touched it at all in this conversation.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to append a poem to a note file.
    //   2. In a second turn, ask the AI to sign the note with an author name.
    //   3. Double-click the file in the diff viewer.
    //   4. The "before" side must show the original file (no poem, no signature).
    //      If it shows the file with the poem already present, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `diff preview before shows original content across multiple turns`() {
        val projectBase = "/project"
        val generation = 1L
        val file = "notes/note1.md"
        val absFile = "$projectBase/$file"
        val originalContent = "# Note\n\nOriginal content.\n"
        val afterPoem = originalContent + "\n## Poem\n\nRoses are red.\n"
        val afterSignature = afterPoem + "\n— Cipher Moonwhisper\n"

        val stateStore = DiffStateStore()
        val stateLock = Any()
        val eventReducer = DiffEventReducer()
        val disk = mutableMapOf<String, String>()

        val computer = SessionDiffApplyComputer(
            contentReader = { absPath -> disk[absPath] ?: "" },
            hunkComputer = { fileDiff, sid ->
                if (fileDiff.before == fileDiff.after) emptyList()
                else listOf(DiffHunk(fileDiff.file, 0,
                    if (fileDiff.before.isEmpty()) emptyList() else listOf(fileDiff.before),
                    if (fileDiff.after.isEmpty()) emptyList() else listOf(fileDiff.after),
                    sid))
            },
            log = NoOpLogger,
            tracer = NoOpDiffTracer,
        )

        fun runTurn(sessionId: String, content: String, nowMillis: Long) {
            disk[absFile] = content
            stateStore.commitTurnPatch(
                stateLock = stateLock,
                sessionId = sessionId,
                touchedPaths = setOf(absFile),
                expectedGeneration = generation,
                currentGeneration = { generation },
            )
            val revision = stateStore.reserveRevisionForSessionDiffApply(
                stateLock = stateLock,
                sessionId = sessionId,
                fromHistory = false,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!
            val turnScope = stateStore.consumeTurnScopeForDiff(
                stateLock = stateLock,
                sessionId = sessionId,
                fromHistory = false,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )
            val prepareSnapshot = stateStore.snapshotSessionDiffPrepareState(
                stateLock = stateLock,
                sessionId = sessionId,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!
            val event = OpenCodeEvent.SessionDiff(
                sessionId = sessionId,
                files = listOf(OpenCodeEvent.SessionDiffFile(
                    file = absFile, before = "", after = "", additions = 1, deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )),
            )
            val computedState = computer.compute(
                projectBase = projectBase,
                event = event,
                fromHistory = false,
                turnScope = turnScope,
                previousAfterByFile = prepareSnapshot.previousAfterByFile,
            )
            stateStore.commitSessionDiffApply(
                stateLock = stateLock,
                sessionId = sessionId,
                revision = revision,
                fromHistory = false,
                computedState = computedState,
                nowMillis = nowMillis,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )
        }

        // File starts at original content; poem session runs first.
        disk[absFile] = originalContent
        runTurn("ses_poem", afterPoem, nowMillis = 1000L)

        // Sign session runs after; its baseline is afterPoem, not originalContent.
        runTurn("ses_sign", afterSignature, nowMillis = 2000L)

        // Simulate getFileDiffPreview: pick the session with the most-recent updatedAt
        // that has a baseline for this file, return (before=baseline, after=currentDisk).
        val candidateSessionIds = listOf("ses_poem", "ses_sign")
            .sortedByDescending { stateStore.updatedAtBySession[it] ?: 0L }

        val (pickedSessionId, pickedBefore) = candidateSessionIds
            .mapNotNull { sessionId ->
                val before = stateStore.baselineBeforeBySessionAndFile[sessionId]?.get(absFile)
                    ?: return@mapNotNull null
                sessionId to before
            }
            .first()

        // The diff preview must use the original content as "before" so that opening
        // the diff viewer shows ALL AI changes, not just the signature.
        assertEquals(
            originalContent,
            pickedBefore,
            "diff preview 'before' must be the original file content, but picked session=$pickedSessionId with before.length=${pickedBefore.length} (afterPoem.length=${afterPoem.length}, originalContent.length=${originalContent.length})",
        )
    }
}
