package com.ashotn.opencode.relay.session

import com.ashotn.opencode.relay.core.session.SessionScopeResolver
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for session selection behaviour.
 */
class SessionSelectionTest {

    private val resolver = SessionScopeResolver()

    // -------------------------------------------------------------------------
    // With no prior selection the resolver returns null — no auto-selection.
    // Sessions are only selected by explicit user interaction.
    //
    // MANUAL VERIFICATION:
    //   1. Start the plugin with sessions already present.
    //   2. No session should be auto-selected in the tool window.
    //   3. Let the AI make changes so that at least one session with diffs
    //      appears in the tool window.
    //   4. Click "Clear Session" (Alt+Shift+Escape).
    //   5. All diff highlights should disappear and no session should be selected.
    // -------------------------------------------------------------------------
    @Test
    fun `resolveSelectedSessionId returns null when no session is selected`() {
        val session1 = "ses_001"
        val session2 = "ses_002"

        val knownSessionIds = setOf(session1, session2)

        assertNull(
            resolver.resolveSelectedSessionId(
                selectedSessionId = null,
                knownSessionIds = knownSessionIds,
            ),
            "with no prior selection the resolver should return null",
        )
    }

    @Test
    fun `resolveSelectedSessionId returns selected session if it is known`() {
        val session1 = "ses_001"
        val session2 = "ses_002"

        val knownSessionIds = setOf(session1, session2)

        assertEquals(
            session1,
            resolver.resolveSelectedSessionId(
                selectedSessionId = session1,
                knownSessionIds = knownSessionIds,
            ),
            "resolver should return the selected session when it is known",
        )
    }

    @Test
    fun `resolveSelectedSessionId returns null if selected session is no longer known`() {
        val session1 = "ses_001"

        assertNull(
            resolver.resolveSelectedSessionId(
                selectedSessionId = session1,
                knownSessionIds = emptySet(),
            ),
            "resolver should return null when selected session is no longer known",
        )
    }
}
