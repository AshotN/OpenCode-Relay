package com.ashotn.opencode.session

import com.ashotn.opencode.diff.SessionScopeResolver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for session selection and clearing behaviour.
 */
class SessionSelectionTest {

    private val resolver = SessionScopeResolver()

    // -------------------------------------------------------------------------
    // With no prior selection the resolver auto-selects the newest session so
    // diffs are visible immediately on startup. With an explicit clear it must
    // return null so that the Clear Session action takes effect.
    //
    // MANUAL VERIFICATION:
    //   1. Let the AI make changes so that at least one session with diffs
    //      appears in the tool window.
    //   2. Click "Clear Session" (Alt+Shift+Escape).
    //   3. All diff highlights should disappear and no session should be selected.
    // -------------------------------------------------------------------------
    @Test
    fun `resolveSelectedSessionId auto-selects newest session when no selection exists, returns null after explicit clear`() {
        val session1 = "ses_001"
        val session2 = "ses_002"

        val knownSessionIds = setOf(session1, session2)
        val updatedAtBySession = mapOf(session1 to 1000L, session2 to 2000L)
        val busyBySession = mapOf(session1 to false, session2 to false)
        val parentBySessionId = emptyMap<String, String>()

        assertEquals(
            session2,
            resolver.resolveSelectedSessionId(
                selectedSessionId = null,
                knownSessionIds = knownSessionIds,
                updatedAtBySession = updatedAtBySession,
                busyBySession = busyBySession,
                parentBySessionId = parentBySessionId,
                explicitlyCleared = false,
            ),
            "with no prior selection the resolver should pick the newest session",
        )

        assertNull(
            resolver.resolveSelectedSessionId(
                selectedSessionId = null,
                knownSessionIds = knownSessionIds,
                updatedAtBySession = updatedAtBySession,
                busyBySession = busyBySession,
                parentBySessionId = parentBySessionId,
                explicitlyCleared = true,
            ),
            "after an explicit clear the resolver must return null",
        )
    }
}
