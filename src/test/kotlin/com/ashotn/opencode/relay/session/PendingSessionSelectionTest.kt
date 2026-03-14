package com.ashotn.opencode.relay.session

import com.ashotn.opencode.relay.core.session.PendingSessionSelection
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingSessionSelectionTest {

    @Test
    fun `defers selection when requested session is not known`() {
        val pending = PendingSessionSelection()

        val deferred = pending.shouldDeferSelection("ses_new") { known ->
            known == "ses_existing"
        }

        assertTrue(deferred)
        assertNull(pending.consumeIfResolved(setOf("ses_existing")))
        assertEquals("ses_new", pending.consumeIfResolved(setOf("ses_existing", "ses_new")))
    }

    @Test
    fun `does not defer when requested session is already known`() {
        val pending = PendingSessionSelection()

        val deferred = pending.shouldDeferSelection("ses_existing") { known ->
            known == "ses_existing"
        }

        assertFalse(deferred)
        assertNull(pending.consumeIfResolved(setOf("ses_existing")))
    }

    @Test
    fun `clears pending selection when selecting null`() {
        val pending = PendingSessionSelection()

        pending.shouldDeferSelection("ses_new") { false }
        val deferred = pending.shouldDeferSelection(null) { false }

        assertFalse(deferred)
        assertNull(pending.consumeIfResolved(setOf("ses_new")))
    }
}
