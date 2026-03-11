package com.ashotn.opencode.companion.lifecycle

import com.ashotn.opencode.companion.core.DiffPipelineHarness
import com.ashotn.opencode.companion.core.StateStore
import com.ashotn.opencode.companion.ipc.SessionDiffStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [StateStore.resetState] — the core of the "Reset Connection"
 * action — clears all accumulated client-side state after real diff activity.
 *
 * The test populates the store via the full pipeline (turn.patch → session.diff)
 * to ensure resetState() is exercised against realistic data, not an empty store.
 */
class ResetConnectionTest {

    // -------------------------------------------------------------------------
    // After populating real diff state across multiple sessions and then calling
    // resetState(), every collection in the store must be empty and all scalar
    // fields must be back to their initial values. Stale state left behind would
    // cause incorrect diff highlights or phantom sessions after reconnecting.
    //
    // MANUAL VERIFICATION:
    //   1. Let the AI modify files so that diff highlights appear in the editor.
    //   2. Click the "Reset OpenCode" button in the tool window toolbar.
    //   3. All diff highlights should disappear immediately, as if the plugin
    //      had just started fresh.
    // -------------------------------------------------------------------------
    @Test
    fun `resetState clears all diff state after real pipeline activity`() {
        val h = DiffPipelineHarness()

        // Populate state: two files modified, one added
        h.disk[h.abs("src/Main.kt")] = "fun main() {}\n"
        h.disk[h.abs("src/Util.kt")] = "fun util() {}\n"
        h.disk[h.abs("src/New.kt")] = ""

        h.commitTurnPatch(listOf("src/Main.kt", "src/Util.kt", "src/New.kt"))
        h.applySessionDiff(
            listOf(
                "src/Main.kt" to SessionDiffStatus.MODIFIED,
                "src/Util.kt" to SessionDiffStatus.MODIFIED,
                "src/New.kt" to SessionDiffStatus.ADDED,
            )
        )

        // Also set a selected session and a pending turn patch to verify those are cleared
        h.stateStore.commitSelectedSession(
            stateLock = h.stateLock,
            requestedSessionId = h.sessionId,
            sessionExists = { true },
        )
        h.commitTurnPatch(listOf("src/Main.kt"))

        // Pre-condition: store has real data
        assertTrue(h.hunkFiles().isNotEmpty(), "pre-condition: hunkFiles should be populated")
        assertEquals(h.sessionId, h.stateStore.selectedSessionId, "pre-condition: selectedSessionId should be set")
        assertTrue(
            h.stateStore.pendingTurnFilesBySession.isNotEmpty(),
            "pre-condition: pendingTurnFilesBySession should be populated"
        )

        // Act: reset (mirrors what stopListening() calls internally)
        h.stateStore.resetState()

        // Assert: every field on the store matches a freshly constructed instance.
        // Uses reflection so that any new field added to StateStore is automatically
        // covered — no manual update to this assertion is ever needed.
        assertMatchesFreshStore(h.stateStore)
    }

    // -------------------------------------------------------------------------
    // After a reset, the pipeline must accept new state as if starting fresh.
    // A session diff applied after reset must be treated as a first-ever event —
    // no stale revision counters or baseline data should interfere.
    //
    // MANUAL VERIFICATION:
    //   1. Let the AI modify a file so highlights appear.
    //   2. Click "Reset OpenCode".
    //   3. Without restarting the server, let the AI modify the same file again.
    //   4. Only the new changes should be highlighted — no ghost highlights from
    //      the pre-reset session.
    // -------------------------------------------------------------------------
    @Test
    fun `pipeline accepts new state cleanly after reset`() {
        val h = DiffPipelineHarness()

        // First round of activity
        h.disk[h.abs("note.md")] = "original\n"
        h.commitTurnPatch(listOf("note.md"))
        h.applySessionDiff(listOf("note.md" to SessionDiffStatus.MODIFIED))
        assertEquals(setOf(h.abs("note.md")), h.hunkFiles(), "pre-reset: file should be tracked")

        // Reset
        h.stateStore.resetState()
        assertTrue(h.hunkFiles().isEmpty(), "post-reset: hunkFiles should be empty")

        // Second round of activity after reset — must work as if starting fresh
        h.disk[h.abs("note.md")] = "new content\n"
        h.commitTurnPatch(listOf("note.md"))
        val result = h.applySessionDiff(listOf("note.md" to SessionDiffStatus.MODIFIED))

        assertEquals(setOf(h.abs("note.md")), h.hunkFiles(), "post-reset activity: file should be tracked again")
        assertEquals(
            setOf(h.abs("note.md")),
            result?.changedFiles,
            "post-reset activity: changedFiles should reflect new state"
        )
        // The baseline after reset should be the empty string (file had no prior content as far
        // as the fresh pipeline is concerned), not the "original\n" content from before the reset.
        assertEquals(
            "",
            h.baseline("note.md"),
            "post-reset activity: baseline should reflect fresh-start content, not pre-reset data"
        )
    }
}

/**
 * Asserts that every field of [store] matches the corresponding field on a freshly
 * constructed [StateStore]. Uses reflection (including private fields) so new
 * fields are automatically covered without any changes to this helper or the test.
 */
private fun assertMatchesFreshStore(store: StateStore) {
    val fresh = StateStore()
    val mismatches = StateStore::class.java.declaredFields.mapNotNull { field ->
        field.isAccessible = true
        val actual = field.get(store)
        val expected = field.get(fresh)
        if (actual != expected) "  ${field.name}: expected $expected but was $actual" else null
    }
    assertTrue(
        mismatches.isEmpty(),
        "store should match a fresh StateStore after reset, but these fields differ:\n${mismatches.joinToString("\n")}"
    )
}
