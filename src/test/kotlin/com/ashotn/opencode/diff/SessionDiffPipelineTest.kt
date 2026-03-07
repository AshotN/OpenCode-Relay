package com.ashotn.opencode.diff

import com.ashotn.opencode.ipc.SessionDiffStatus
import org.junit.Test
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
}
