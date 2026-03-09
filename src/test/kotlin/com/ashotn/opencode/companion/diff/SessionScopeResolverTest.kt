package com.ashotn.opencode.companion.diff

import org.junit.Test
import kotlin.test.assertEquals

class SessionScopeResolverTest {

    private val resolver = SessionScopeResolver()

    // -------------------------------------------------------------------------
    // When two separate sessions each create a file, the modified-files list for
    // each session must show only the file(s) that session touched. Switching
    // from Session 1 to Session 2 must not carry Session 1's files into Session
    // 2's list, and vice versa. The lists must be independent regardless of how
    // recently each session was active.
    //
    // MANUAL VERIFICATION:
    //   1. In Session 1, ask the AI to create note1.md.
    //      The modified-files list for Session 1 should show only note1.md.
    //   2. Create a new Session 2 and ask it to create note2.md.
    //      The modified-files list for Session 2 should show only note2.md.
    //   3. Switch back to Session 1.
    //      The list should show only note1.md — not both files.
    //   4. If both files appear in both sessions, this invariant is violated.
    // -------------------------------------------------------------------------
    @Test
    fun `switching between unrelated sessions shows only each session's own files`() {
        val session1 = "ses_001"
        val session2 = "ses_002"
        val file1 = "/project/note1.md"
        val file2 = "/project/note2.md"

        // Both sessions were recently active (within the 60-second recent window).
        val t0 = 1_000_000L
        val nowMillis = t0 + 5_000L  // 5 seconds later — both sessions are "recent"

        val knownSessionIds = setOf(session1, session2)
        val parentBySessionId = emptyMap<String, String>()
        val busyBySession = mapOf(session1 to false, session2 to false)
        val updatedAtBySession = mapOf(session1 to t0, session2 to t0 + 1_000L)

        // Each session has diff state for its own file only.
        val hunksBySessionAndFile = mapOf(
            session1 to mapOf(file1 to listOf(DiffHunk(file1, 0, emptyList(), listOf("note1 content"), session1))),
            session2 to mapOf(file2 to listOf(DiffHunk(file2, 0, emptyList(), listOf("note2 content"), session2))),
        )

        // When Session 1 is selected, only file1 should be in the family scope.
        val familyForSession1 = resolver.familySessionIds(
            selectedSessionId = session1,
            parentBySessionId = parentBySessionId,
            knownSessionIds = knownSessionIds,
            busyBySession = busyBySession,
            updatedAtBySession = updatedAtBySession,
            hunksBySessionAndFile = hunksBySessionAndFile,
            nowMillis = nowMillis,
        )

        assertEquals(
            setOf(session1),
            familyForSession1,
            "Session 1's family must contain only Session 1 — not Session 2 — " +
                "but got: $familyForSession1",
        )

        // When Session 2 is selected, only file2 should be in the family scope.
        val familyForSession2 = resolver.familySessionIds(
            selectedSessionId = session2,
            parentBySessionId = parentBySessionId,
            knownSessionIds = knownSessionIds,
            busyBySession = busyBySession,
            updatedAtBySession = updatedAtBySession,
            hunksBySessionAndFile = hunksBySessionAndFile,
            nowMillis = nowMillis,
        )

        assertEquals(
            setOf(session2),
            familyForSession2,
            "Session 2's family must contain only Session 2 — not Session 1 — " +
                "but got: $familyForSession2",
        )
    }
}
