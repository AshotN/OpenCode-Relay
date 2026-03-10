package com.ashotn.opencode.companion.diff

import com.ashotn.opencode.companion.ipc.OpenCodeEvent
import com.ashotn.opencode.companion.ipc.SessionDiffStatus
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
    // When a root session delegates work to parallel sub-agents, all files
    // touched by those sub-agents count as part of the same logical turn of the
    // root session. All files must appear as live (inline-highlightable) at the
    // same time — not just the file from the last sub-agent to finish.
    //
    // This aligns with Rule 2 of the inline diff policy: sub-agents running
    // under a single root session represent one turn from the user's perspective.
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
        assertEquals(
            allExpectedFiles,
            liveVisibleWithoutHierarchy,
            "All child-session files must be visible under the root session even before hierarchy refresh, got: $liveVisibleWithoutHierarchy",
        )
    }

    // -------------------------------------------------------------------------
    // When the AI intentionally empties a file (replaces all content with an
    // empty string), the "After AI" panel in the diff viewer must show an empty
    // file. If a previous turn wrote content to the same file, the diff viewer
    // must not show that earlier content in the "After AI" panel — the user
    // would be looking at the wrong thing and could make incorrect decisions
    // about accepting or reverting the change.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to add a joke to note.md.
    //   2. In a second turn, ask the AI to replace note.md with an empty string.
    //   3. Double-click note.md in the diff viewer.
    //   4. The "After AI" panel must be empty.
    //      If it still shows the joke from turn 1, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `After AI panel must be empty when AI empties a file`() {
        val file = "note.md"
        val originalContent = "# Notes\n\nSome original text.\n"
        val withJoke = originalContent + "\nWhy do Java developers wear glasses? Because they can't C#.\n"

        // Turn 1: AI adds a joke — server sends non-empty after
        h.disk[h.abs(file)] = withJoke
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(
            files = listOf(file to SessionDiffStatus.MODIFIED),
            serverAfterByFile = mapOf(h.abs(file) to withJoke),
        )
        assertEquals(withJoke, h.serverAfter(file),
            "after turn 1: serverAfter should hold the joke content")

        // Turn 2: AI empties the file — server sends empty string as after
        h.disk[h.abs(file)] = ""
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(
            files = listOf(file to SessionDiffStatus.MODIFIED),
            serverAfterByFile = mapOf(h.abs(file) to ""),
        )

        // The "After AI" content shown in the diff viewer must be empty string,
        // not the stale joke content from turn 1.
        assertEquals(
            "",
            h.serverAfter(file),
            "After AI panel must show empty string when AI emptied the file, " +
                "but serverAfter still holds stale content: '${h.serverAfter(file)}'",
        )
    }

    // -------------------------------------------------------------------------
    // A file the AI modified must still appear in the session's file count even
    // when the diff computation throws an unexpected error. If hunk computation
    // fails internally, the file silently disappears from tracking — the session
    // panel shows (0) and the file is absent from the list, even though the AI
    // clearly changed it. The user has no indication the AI touched the file at all.
    //
    // MANUAL VERIFICATION:
    //   There is no reliable way to force this from the UI — it depends on an
    //   internal exception in hunk computation (e.g. from ComparisonManager).
    //   Check the IDE log for "failed to compute hunks" warnings. If that warning
    //   appears for a file but the session panel shows (0) and the file is absent
    //   from the file list, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `file must still appear in session file count when hunk computation produces no hunks`() {
        val file = "note.md"

        // Use a harness with a hunk computer that always returns empty — regardless
        // of why (failed diff, ComparisonManager exception, algorithmic edge case).
        // The invariant is about the outcome (zero hunks) not the mechanism.
        val zeroHunkHarness = DiffPipelineHarness(
            hunkComputer = { _, _ -> emptyList() }
        )

        zeroHunkHarness.disk[zeroHunkHarness.abs(file)] = "line1\n"
        zeroHunkHarness.commitTurnPatch(listOf(file))
        zeroHunkHarness.applySessionDiff(
            files = listOf(file to SessionDiffStatus.MODIFIED),
            serverAfterByFile = mapOf(zeroHunkHarness.abs(file) to "line1\n"),
        )

        // The file was reported as MODIFIED — it must still be counted.
        assertEquals(
            1,
            zeroHunkHarness.trackedFileCount(),
            "a MODIFIED file must still be counted when hunk computation produces no hunks, got: ${zeroHunkHarness.trackedFileCount()}",
        )
    }

    // -------------------------------------------------------------------------
    // Once the AI has modified a file in a session, that file must remain
    // visible in the session's file list permanently — even if the user
    // manually reverts the file back to its original content. The user should
    // always be able to open the 3-panel diff viewer to see what the AI did
    // and restore it if they want. Removing the file from the list takes away
    // that option with no way to get it back.
    //
    // MANUAL VERIFICATION:
    //   1. Create an empty note.md.
    //   2. Ask the AI to write a joke into note.md.
    //      note.md appears in the session's file list.
    //   3. Manually delete the AI's content so note.md is empty again.
    //   4. Restart the IDE.
    //      note.md must still appear in the session's file list after the reset.
    //      If it disappears, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `file must remain in session list after user reverts it to original content`() {
        val file = "note.md"
        val original = ""   // file was empty before the AI touched it
        val aiContent = "Why do Java developers wear glasses? Because they can't C#.\n"

        // AI writes content to the previously empty file
        h.disk[h.abs(file)] = aiContent
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(
            files = listOf(file to SessionDiffStatus.MODIFIED),
            serverAfterByFile = mapOf(h.abs(file) to aiContent),
        )
        assertEquals(1, h.trackedFileCount(), "file should be tracked after AI wrote to it")

        // User reverts the file back to original — disk now matches baseline
        h.disk[h.abs(file)] = original

        // Reconciler runs (simulated by running it directly)
        val snapshot = h.stateStore.snapshotSessionReconcileState(
            stateLock = h.stateLock,
            sessionId = h.sessionId,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )!!
        val decision = h.eventReducer.reduceReconcile(
            currentHunks = snapshot.currentHunks,
            currentDeleted = snapshot.currentDeleted,
            currentAdded = snapshot.currentAdded,
            currentBaselines = snapshot.currentBaselines,
            readCurrentContent = { absPath -> h.disk[absPath] ?: "" },
        )!!
        h.stateStore.commitReconcile(
            stateLock = h.stateLock,
            sessionId = h.sessionId,
            revision = h.stateStore.currentSessionRevision(h.stateLock, h.sessionId),
            updatedHunks = decision.updatedHunks,
            updatedDeleted = decision.updatedDeleted,
            updatedAdded = decision.updatedAdded,
            currentBaselines = snapshot.currentBaselines,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )

        // The file must still be in the list — the user needs to be able to
        // open the diff viewer to see and restore what the AI did.
        // Currently fails: reconciler removes the file from hunksBySessionAndFile
        // entirely when disk matches baseline.
        assertEquals(
            1,
            h.trackedFileCount(),
            "file must remain in session list after user reverts it, got: ${h.trackedFileCount()}",
        )
    }

    // -------------------------------------------------------------------------
    // After an IDE restart, the plugin reloads session history from the server
    // but must not restore inline green/red highlights. Inline highlights are a
    // runtime signal — they only appear after a new turn runs in the current
    // IDE session. Reloading history is for file counts and diff preview only.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to modify a file. Green highlights appear in the editor.
    //   2. Restart the IDE.
    //   3. Open the same file — no green highlights should be visible.
    //      The session should still appear in the session panel with a file count,
    //      but the editor must be clean until a new turn runs.
    // -------------------------------------------------------------------------
    @Test
    fun `historical reload after restart does not restore inline highlights`() {
        val file = "note.md"

        // Simulate a live turn that produced inline highlights before restart.
        h.disk[h.abs(file)] = "line1\n"
        h.commitTurnPatch(listOf(file))
        h.applySessionDiff(listOf(file to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file)), h.liveHunkFiles(), "pre-restart: live highlights present")

        // Simulate IDE restart: create fresh state store and replay the session
        // diff as a historical load (fromHistory=true), exactly as the plugin does
        // when it calls /session/{id}/diff on startup.
        val freshStore = DiffStateStore()
        val freshLock = Any()
        val revision = freshStore.reserveRevisionForSessionDiffApply(
            stateLock = freshLock,
            sessionId = h.sessionId,
            fromHistory = true,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )!!
        val prepareSnapshot = freshStore.snapshotSessionDiffPrepareState(
            stateLock = freshLock,
            sessionId = h.sessionId,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )!!
        val computer = SessionDiffApplyComputer(
            contentReader = { absPath -> h.disk[absPath] ?: "" },
            hunkComputer = { fileDiff, sid ->
                if (fileDiff.before == fileDiff.after) emptyList()
                else listOf(DiffHunk(fileDiff.file, 0, emptyList(), listOf(fileDiff.after), sid))
            },
            log = NoOpLogger,
            tracer = NoOpDiffTracer,
        )
        val event = OpenCodeEvent.SessionDiff(
            sessionId = h.sessionId,
            files = listOf(OpenCodeEvent.SessionDiffFile(
                file = h.abs(file),
                before = "",
                after = "line1\n",
                additions = 1,
                deletions = 0,
                status = SessionDiffStatus.MODIFIED,
            )),
        )
        val computedState = computer.compute(
            projectBase = h.projectBase,
            event = event,
            fromHistory = true,
            turnScope = null,
            previousAfterByFile = prepareSnapshot.previousAfterByFile,
        )
        freshStore.commitSessionDiffApply(
            stateLock = freshLock,
            sessionId = h.sessionId,
            revision = revision,
            fromHistory = true,
            computedState = computedState,
            nowMillis = 0L,
            expectedGeneration = h.generation,
            currentGeneration = { h.generation },
        )

        // After historical reload: file count is tracked but inline highlights are gone.
        val liveAfterReload = freshStore.liveHunksBySessionAndFile[h.sessionId]?.keys ?: emptySet()
        val cumAfterReload = freshStore.hunksBySessionAndFile[h.sessionId]?.keys ?: emptySet()

        assertTrue(liveAfterReload.isEmpty(),
            "post-restart historical reload must not restore inline highlights, got liveHunkFiles: $liveAfterReload")
        assertEquals(setOf(h.abs(file)), cumAfterReload,
            "cumulative hunk state must be populated for file count / diff preview")
    }

    // -------------------------------------------------------------------------
    // After a turn that only modifies one file, only that file should show green
    // or red inline highlights in the editor. Files touched by previous turns
    // must not continue to show highlights just because they were changed
    // earlier in the session.
    //
    // MANUAL VERIFICATION:
    //   1. Ask the AI to write content to note1.md in one turn.
    //   2. In a second turn, ask the AI to write content to note2.md.
    //   3. Open note1.md — it must NOT have any green highlights.
    //      Only note2.md should show green highlights after turn 2.
    // -------------------------------------------------------------------------
    @Test
    fun `inline highlights only show for the most recent turn's files`() {
        val file1 = "note1.md"
        val file2 = "note2.md"
        val file3 = "note3.md"

        // Turn 1: touch note1.md
        h.disk[h.abs(file1)] = "line1\n"
        h.commitTurnPatch(listOf(file1))
        h.applySessionDiff(listOf(file1 to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs(file1)), h.liveHunkFiles(), "after turn 1: only note1.md should be live")

        // Turn 2: touch note2.md only — server diff now reports both files cumulatively
        h.disk[h.abs(file2)] = "line2\n"
        h.commitTurnPatch(listOf(file2))
        h.applySessionDiff(listOf(
            file1 to SessionDiffStatus.MODIFIED,
            file2 to SessionDiffStatus.MODIFIED,
        ))
        assertEquals(
            setOf(h.abs(file2)),
            h.liveHunkFiles(),
            "after turn 2: only note2.md should be live — note1.md was not touched this turn",
        )

        // Turn 3: touch note3.md only — server diff now reports all three files cumulatively
        h.disk[h.abs(file3)] = "line3\n"
        h.commitTurnPatch(listOf(file3))
        h.applySessionDiff(listOf(
            file1 to SessionDiffStatus.MODIFIED,
            file2 to SessionDiffStatus.MODIFIED,
            file3 to SessionDiffStatus.ADDED,
        ))
        assertEquals(
            setOf(h.abs(file3)),
            h.liveHunkFiles(),
            "after turn 3: only note3.md should be live — note1.md and note2.md were not touched this turn",
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
    // it across multiple turns (e.g. add poem in turn 1, add signature in turn 2),
    // the diff must show all changes from the original file — not just the most
    // recent turn's change. The "before" side of the diff must always be the content
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
        val afterPoem = "$originalContent\n## Poem\n\nRoses are red.\n"
        val afterSignature = "$afterPoem\n— Cipher Moonwhisper\n"

        val stateStore = DiffStateStore()
        val stateLock = Any()
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

        // Simulate what the server returns for GET /session/{id}/diff:
        // it always carries the true original "before" for each file, regardless of
        // how many live turns have run. We model this with fromHistory=true, which
        // makes SessionDiffApplyComputer use diffFile.before directly.
        fun simulateServerDiffFetch(sessionId: String, serverBefore: String, currentContent: String) {
            disk[absFile] = currentContent
            val revision = stateStore.reserveRevisionForSessionDiffApply(
                stateLock = stateLock,
                sessionId = sessionId,
                fromHistory = true,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!
            val prepareSnapshot = stateStore.snapshotSessionDiffPrepareState(
                stateLock = stateLock,
                sessionId = sessionId,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )!!
            val event = OpenCodeEvent.SessionDiff(
                sessionId = sessionId,
                files = listOf(OpenCodeEvent.SessionDiffFile(
                    file = absFile,
                    before = serverBefore, // server's authoritative original
                    after = currentContent,
                    additions = 1,
                    deletions = 0,
                    status = SessionDiffStatus.MODIFIED,
                )),
            )
            val computedState = computer.compute(
                projectBase = projectBase,
                event = event,
                fromHistory = true,
                turnScope = null,
                previousAfterByFile = prepareSnapshot.previousAfterByFile,
            )
            stateStore.commitSessionDiffApply(
                stateLock = stateLock,
                sessionId = sessionId,
                revision = revision,
                fromHistory = true,
                computedState = computedState,
                nowMillis = 1000L,
                expectedGeneration = generation,
                currentGeneration = { generation },
            )
        }

        // The server is fetched for the most recently active session (sign).
        // It returns originalContent as "before" — the state before any AI edits.
        simulateServerDiffFetch(
            sessionId = "ses_sign",
            serverBefore = originalContent,
            currentContent = afterSignature,
        )

        // The baseline stored from the server fetch must be the original content,
        // not the intermediate post-poem content.
        val storedBefore = stateStore.baselineBeforeBySessionAndFile["ses_sign"]?.get(absFile)

        assertEquals(
            originalContent,
            storedBefore,
            "diff preview 'before' must be the server-provided original file content, " +
                "but got before.length=${storedBefore?.length} " +
                "(afterPoem.length=${afterPoem.length}, originalContent.length=${originalContent.length})",
        )
    }
}
